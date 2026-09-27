package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.example.pokemoncollection.config.PokemonCacheProperties;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PokemonLookupServiceTest {
    private final PokemonCacheRepository repository = mock(PokemonCacheRepository.class);
    private final PokeApiClient pokeApiClient = mock(PokeApiClient.class);
    private final CacheManager cacheManager = new CaffeineCacheManager("pokemonById", "pokemonByName");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-23T12:00:00Z");
    private PokemonLookupService service;

    @BeforeEach
    void setUp() {
        PokemonCacheProperties properties = new PokemonCacheProperties();
        properties.setTtlDays(7);
        service = new PokemonLookupService(repository, pokeApiClient, cacheManager, properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void l1HitDoesNotCallDatabaseOrApi(CapturedOutput output) {
        var cached = new PokemonDetailDto(25, "pikachu", "sprite", List.of("electric"));
        cacheManager.getCache("pokemonById").put(25, cached);

        assertThat(service.getById(25)).isEqualTo(cached);
        verifyNoInteractions(repository, pokeApiClient);
        assertThat(output).doesNotContain("event=CACHE_L1_HIT");
    }

    @Test
    void l1HitIsLoggedWhenLookupLoggerIsDebug(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(PokemonLookupService.class);
        Level previousLevel = logger.getLevel();
        try {
            logger.setLevel(Level.DEBUG);
            var cached = new PokemonDetailDto(
                    25, "pikachu", "sprite", List.of("electric"));
            cacheManager.getCache("pokemonById").put(25, cached);

            service.getById(25);

            assertThat(output).contains("event=CACHE_L1_HIT by=id key=25");
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void freshL2HitWritesBothL1Keys(CapturedOutput output) throws Exception {
        PokemonCache cache = cache(25, "pikachu", now.minusSeconds(1));
        when(repository.findByPokemonIdAndDeletedFalse(25)).thenReturn(Optional.of(cache));

        var result = service.getById(25);

        assertThat(result.name()).isEqualTo("pikachu");
        assertThat(cacheManager.getCache("pokemonByName").get("pikachu").get()).isEqualTo(result);
        verifyNoInteractions(pokeApiClient);
        assertThat(output).contains("event=CACHE_L1_MISS by=id key=25")
                .contains("event=CACHE_L2_HIT by=id key=25 pokemonId=25");
    }

    @Test
    void staleL2RowFetchesApiAndUpdatesIt(CapturedOutput output) throws Exception {
        PokemonCache existing = cache(25, "pikachu", now.minusSeconds(7 * 24 * 60 * 60L + 1));
        when(repository.findByPokemonIdAndDeletedFalse(25)).thenReturn(Optional.of(existing));
        when(repository.findByPokemonId(25)).thenReturn(Optional.of(existing));
        when(pokeApiClient.fetchByIdentifier("25")).thenReturn(objectMapper.readTree("""
                {"id":25,"name":"pikachu","sprites":{"front_default":"new-sprite"},"types":[]}
                """));
        when(repository.save(existing)).thenReturn(existing);

        assertThat(service.getById(25).spriteUrl()).isEqualTo("new-sprite");
        verify(pokeApiClient).fetchByIdentifier("25");
        assertThat(existing.getCachedAt()).isEqualTo(now);
        assertThat(output).contains("event=CACHE_L1_MISS by=id key=25")
                .contains("event=CACHE_L2_MISS by=id key=25 reason=stale cachedAt=2026-09-16T11:59:59Z")
                .doesNotContain("event=CACHE_L2_HIT");
    }

    @Test
    void notFoundDoesNotWriteToCaches(CapturedOutput output) {
        when(repository.findByPokemonIdAndDeletedFalse(999)).thenReturn(Optional.empty());
        when(repository.findByPokemonId(999)).thenReturn(Optional.empty());
        when(pokeApiClient.fetchByIdentifier("999")).thenThrow(new PokemonNotFoundException("999"));

        assertThatThrownBy(() -> service.getById(999)).isInstanceOf(PokemonNotFoundException.class);
        assertThat(cacheManager.getCache("pokemonById").get(999)).isNull();
        verify(repository, never()).save(any());
        assertThat(output).contains("event=CACHE_L2_MISS by=id key=999 reason=absent")
                .doesNotContain("event=POKEMON_CACHE_MARKED_DELETED");
    }

    @Test
    void expiredExistingRowIsMarkedDeletedWhenApiReturnsNotFound(CapturedOutput output) throws Exception {
        PokemonCache existing = cache(25, "pikachu", now.minusSeconds(7 * 24 * 60 * 60L + 1));
        existing.setCacheId(99L);
        when(repository.findByPokemonIdAndDeletedFalse(25)).thenReturn(Optional.of(existing));
        when(repository.findByPokemonId(25)).thenReturn(Optional.of(existing));
        when(pokeApiClient.fetchByIdentifier("25")).thenThrow(new PokemonNotFoundException("25"));
        cacheManager.getCache("pokemonByName").put("pikachu",
                new PokemonDetailDto(25, "pikachu", "sprite", List.of()));

        assertThatThrownBy(() -> service.getById(25)).isInstanceOf(PokemonNotFoundException.class);

        assertThat(existing.isDeleted()).isTrue();
        verify(repository).save(existing);
        assertThat(cacheManager.getCache("pokemonById").get(25)).isNull();
        assertThat(cacheManager.getCache("pokemonByName").get("pikachu")).isNull();
        assertThat(output).contains("event=POKEMON_CACHE_MARKED_DELETED pokemonId=25 name=pikachu");
    }

    @Test
    void successfulRefreshRestoresPreviouslyDeletedRow(CapturedOutput output) throws Exception {
        PokemonCache existing = cache(25, "pikachu", now.minusSeconds(7 * 24 * 60 * 60L + 1));
        existing.setCacheId(99L);
        existing.setDeleted(true);
        when(repository.findByPokemonIdAndDeletedFalse(25)).thenReturn(Optional.empty());
        when(repository.findByPokemonId(25)).thenReturn(Optional.of(existing));
        when(pokeApiClient.fetchByIdentifier("25")).thenReturn(objectMapper.readTree("""
                {"id":25,"name":"pikachu","sprites":{"front_default":"restored-sprite"},"types":[]}
                """));
        when(repository.save(existing)).thenReturn(existing);

        assertThat(service.getById(25).spriteUrl()).isEqualTo("restored-sprite");
        assertThat(existing.isDeleted()).isFalse();
        verify(repository).save(existing);
        assertThat(output).contains("event=CACHE_L2_MISS by=id key=25 reason=absent")
                .contains("event=POKEMON_CACHE_RESTORED pokemonId=25 name=pikachu");
    }

    @Test
    void concurrentIdLookupUsesTheRowWrittenByTheOtherRequest(CapturedOutput output) throws Exception {
        PokemonCache concurrent = cache(25, "pikachu", now);
        when(repository.findByPokemonIdAndDeletedFalse(25))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(repository.findByPokemonId(25)).thenReturn(Optional.empty());
        when(pokeApiClient.fetchByIdentifier("25")).thenReturn(objectMapper.readTree("""
                {"id":25,"name":"pikachu","sprites":{"front_default":"sprite"},"types":[]}
                """));
        when(repository.save(any(PokemonCache.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate pokemon id"));

        assertThat(service.getById(25).name()).isEqualTo("pikachu");
        verify(repository, times(2)).findByPokemonIdAndDeletedFalse(25);
        assertThat(output).contains("event=POKEMON_CACHE_CONCURRENT_WRITE pokemonId=25 name=pikachu");
    }

    @Test
    void concurrentNameLookupUsesTheRowWrittenByTheOtherRequest(CapturedOutput output) throws Exception {
        PokemonCache concurrent = cache(25, "pikachu", now);
        when(repository.findByPokemonNameAndDeletedFalse("pikachu"))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(repository.findByPokemonName("pikachu")).thenReturn(Optional.empty());
        when(pokeApiClient.fetchByIdentifier("pikachu")).thenReturn(objectMapper.readTree("""
                {"id":25,"name":"pikachu","sprites":{"front_default":"sprite"},"types":[]}
                """));
        when(repository.save(any(PokemonCache.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate pokemon name"));

        assertThat(service.getByName(" Pikachu ").pokemonId()).isEqualTo(25);
        verify(repository, times(2)).findByPokemonNameAndDeletedFalse("pikachu");
        assertThat(output).contains("event=CACHE_L1_MISS by=name key=pikachu")
                .contains("event=CACHE_L2_MISS by=name key=pikachu reason=absent")
                .contains("event=POKEMON_CACHE_CONCURRENT_WRITE pokemonId=25 name=pikachu");
    }

    private PokemonCache cache(int id, String name, Instant cachedAt) throws Exception {
        PokemonCache cache = new PokemonCache();
        cache.setPokemonId(id);
        cache.setPokemonName(name);
        cache.setCachedAt(cachedAt);
        cache.setData(objectMapper.readTree("""
                {"id":25,"name":"pikachu","sprites":{"front_default":"sprite"},"types":[{"type":{"name":"electric"}}]}
                """));
        return cache;
    }
}
