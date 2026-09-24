package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.common.exception.CollectionEntryNotFoundException;
import com.example.pokemoncollection.common.exception.DuplicateCollectionEntryException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.example.pokemoncollection.pokemon.PokemonCache;
import com.example.pokemoncollection.pokemon.PokemonCacheRepository;
import com.example.pokemoncollection.pokemon.PokemonLookupService;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class CollectionServiceTest {
    private final PokemonCollectionRepository collectionRepository = mock(PokemonCollectionRepository.class);
    private final PokemonCacheRepository cacheRepository = mock(PokemonCacheRepository.class);
    private final PokemonLookupService lookupService = mock(PokemonLookupService.class);
    private final CollectionEntryWriteService writeService =
            new CollectionEntryWriteService(collectionRepository, cacheRepository);
    private final CollectionService service = new CollectionService(collectionRepository, lookupService, writeService);

    @Test
    void containsChecksPokemonOwnershipForTrainer() {
        when(collectionRepository.existsByTrainerIdAndPokemonCache_PokemonId(1L, 25)).thenReturn(true);

        assertThat(service.contains(1L, 25)).isTrue();
    }

    @Test
    void duplicatePokemonForTrainerIsRejected(CapturedOutput output) {
        when(lookupService.getById(25)).thenReturn(new PokemonDetailDto(25, "pikachu", null, List.of()));
        when(cacheRepository.findActiveCacheIdByPokemonId(25)).thenReturn(Optional.of(10L));
        when(collectionRepository.existsByTrainerIdAndPokemonCache_CacheId(1L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.add(1L, 25)).isInstanceOf(DuplicateCollectionEntryException.class);
        verify(cacheRepository, never()).getReferenceById(anyLong());
        assertThat(output).contains("event=COLLECTION_ADD_DUPLICATE trainerId=1 pokemonId=25")
                .doesNotContain("event=COLLECTION_ADD trainerId");
    }

    @Test
    void deletingAnotherTrainersEntryIsSameAsMissingEntry(CapturedOutput output) {
        when(collectionRepository.findByIdAndTrainerId(55L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(2L, 55L))
                .isInstanceOf(CollectionEntryNotFoundException.class);
        verify(collectionRepository, never()).delete(any());
        assertThat(output).contains("event=COLLECTION_REMOVE_NOT_FOUND trainerId=2 entryId=55");
    }

    @Test
    void removingOwnEntryDeletesItWithoutLoadingCachedPokemon(CapturedOutput output) {
        PokemonCollectionEntry entry = mock(PokemonCollectionEntry.class);
        when(collectionRepository.findByIdAndTrainerId(7L, 1L)).thenReturn(Optional.of(entry));

        service.remove(1L, 7L);

        verify(collectionRepository).delete(entry);
        verify(entry, never()).getPokemonCache();
        assertThat(output).contains("event=COLLECTION_REMOVE trainerId=1 entryId=7");
    }

    @Test
    void localNameFilteringUsesProjectionWithoutRefreshingPokemonApi(CapturedOutput output) {
        CollectionEntryView view = mock(CollectionEntryView.class);
        PageRequest pageable = PageRequest.of(0, 6);
        when(collectionRepository.findEntryViews(1L, "chu", pageable))
                .thenReturn(new PageImpl<>(List.of(view), pageable, 1));
        when(view.getEntryId()).thenReturn(7L);
        when(view.getPokemonId()).thenReturn(25);
        when(view.getName()).thenReturn("pikachu");
        when(view.getSpriteUrl()).thenReturn("sprite");
        when(view.getTypes()).thenReturn(new String[]{"electric"});
        when(view.getCaughtAt()).thenReturn(Instant.parse("2026-09-24T00:00:00Z"));

        assertThat(service.list(1L, "chu", pageable).content())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.pokemonId()).isEqualTo(25);
                    assertThat(entry.spriteUrl()).isEqualTo("sprite");
                    assertThat(entry.types()).containsExactly("electric");
                });
        verifyNoInteractions(lookupService, cacheRepository);
        assertThat(output).contains("event=COLLECTION_LIST trainerId=1 page=0 size=6 query=chu")
                .doesNotContain("event=CACHE_")
                .doesNotContain("event=POKEAPI_");
    }

    @Test
    void nameFilterIsLowercasedAndLikeWildcardsAreEscaped() {
        PageRequest pageable = PageRequest.of(0, 6);
        when(collectionRepository.findEntryViews(1L, "pika\\_\\%", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(1L, " PIKA_% ", pageable);

        verify(collectionRepository).findEntryViews(1L, "pika\\_\\%", pageable);
    }

    @Test
    void addUsesScalarCacheIdAndReReadsTheProjection(CapturedOutput output) {
        PokemonCache cacheReference = mock(PokemonCache.class);
        PokemonCollectionEntry savedEntry = mock(PokemonCollectionEntry.class);
        CollectionEntryView view = mock(CollectionEntryView.class);
        when(lookupService.getById(25)).thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of("electric")));
        when(cacheRepository.findActiveCacheIdByPokemonId(25)).thenReturn(Optional.of(10L));
        when(cacheRepository.getReferenceById(10L)).thenReturn(cacheReference);
        when(collectionRepository.existsByTrainerIdAndPokemonCache_CacheId(1L, 10L)).thenReturn(false);
        when(collectionRepository.save(any(PokemonCollectionEntry.class))).thenReturn(savedEntry);
        when(savedEntry.getId()).thenReturn(7L);
        when(collectionRepository.findEntryView(7L, 1L)).thenReturn(Optional.of(view));
        when(view.getEntryId()).thenReturn(7L);
        when(view.getPokemonId()).thenReturn(25);
        when(view.getName()).thenReturn("pikachu");
        when(view.getSpriteUrl()).thenReturn("sprite");
        when(view.getTypes()).thenReturn(new String[]{"electric"});
        when(view.getCaughtAt()).thenReturn(Instant.parse("2026-09-24T00:00:00Z"));

        var result = service.add(1L, 25);

        assertThat(result.types()).containsExactly("electric");
        verify(cacheRepository).findActiveCacheIdByPokemonId(25);
        verify(cacheRepository).getReferenceById(10L);
        verify(collectionRepository).flush();
        verify(collectionRepository).findEntryView(7L, 1L);
        verifyNoInteractions(cacheReference);
        assertThat(output).contains("event=COLLECTION_ADD trainerId=1 pokemonId=25 entryId=7");
    }

    @Test
    void concurrentDuplicateInsertIsMappedToDuplicateCollectionError(CapturedOutput output) {
        PokemonCache cacheReference = mock(PokemonCache.class);
        when(lookupService.getById(25)).thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of()));
        when(cacheRepository.findActiveCacheIdByPokemonId(25)).thenReturn(Optional.of(10L));
        when(cacheRepository.getReferenceById(10L)).thenReturn(cacheReference);
        when(collectionRepository.existsByTrainerIdAndPokemonCache_CacheId(1L, 10L)).thenReturn(false);
        when(collectionRepository.save(any(PokemonCollectionEntry.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate collection key",
                        new ConstraintViolationException("duplicate collection key", new SQLException(),
                                "pokemon_collection_entry_trainer_id_pokemon_cache_id_key")));

        assertThatThrownBy(() -> service.add(1L, 25))
                .isInstanceOf(DuplicateCollectionEntryException.class);
        assertThat(output).contains("event=COLLECTION_ADD_DUPLICATE trainerId=1 pokemonId=25");
    }

    @Test
    void foreignKeyInsertFailureIsNotReportedAsDuplicateCollection() {
        PokemonCache cacheReference = mock(PokemonCache.class);
        when(lookupService.getById(25)).thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of()));
        when(cacheRepository.findActiveCacheIdByPokemonId(25)).thenReturn(Optional.of(10L));
        when(cacheRepository.getReferenceById(10L)).thenReturn(cacheReference);
        when(collectionRepository.existsByTrainerIdAndPokemonCache_CacheId(1L, 10L)).thenReturn(false);
        when(collectionRepository.save(any(PokemonCollectionEntry.class)))
                .thenThrow(new DataIntegrityViolationException("foreign key",
                        new ConstraintViolationException("foreign key", new SQLException(),
                                "pokemon_collection_entry_pokemon_cache_id_fkey")));

        assertThatThrownBy(() -> service.add(1L, 25))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateCollectionEntryException.class);
    }

    @Test
    void failedPokemonLookupDoesNotInvokeTheCollectionWriteTransaction() {
        CollectionEntryWriteService writeService = mock(CollectionEntryWriteService.class);
        CollectionService serviceWithoutWrite =
                new CollectionService(collectionRepository, lookupService, writeService);
        when(lookupService.getById(999)).thenThrow(new PokemonNotFoundException("999"));

        assertThatThrownBy(() -> serviceWithoutWrite.add(1L, 999))
                .isInstanceOf(PokemonNotFoundException.class);
        verifyNoInteractions(writeService);
    }
}
