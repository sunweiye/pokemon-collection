package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.example.pokemoncollection.config.PokemonCacheProperties;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PokemonLookupService {
    private static final Logger log = LoggerFactory.getLogger(PokemonLookupService.class);

    private final PokemonCacheRepository repository;
    private final PokeApiClient pokeApiClient;
    private final CacheManager cacheManager;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public PokemonLookupService(PokemonCacheRepository repository, PokeApiClient pokeApiClient,
                                CacheManager cacheManager, PokemonCacheProperties properties) {
        this(repository, pokeApiClient, cacheManager, properties, Clock.systemUTC());
    }

    public PokemonLookupService(PokemonCacheRepository repository, PokeApiClient pokeApiClient,
                                CacheManager cacheManager, PokemonCacheProperties properties, Clock clock) {
        this.repository = repository;
        this.pokeApiClient = pokeApiClient;
        this.cacheManager = cacheManager;
        this.ttl = Duration.ofDays(properties.getTtlDays());
        this.clock = clock;
    }

    public PokemonDetailDto getById(Integer pokemonId) {
        Cache cache = requiredCache("pokemonById");
        Cache.ValueWrapper hit = cache.get(pokemonId);
        if (hit != null) {
            log.debug("event=CACHE_L1_HIT by=id key={}", pokemonId);
            return (PokemonDetailDto) hit.get();
        }
        log.info("event=CACHE_L1_MISS by=id key={}", pokemonId);

        var active = repository.findByPokemonIdAndDeletedFalse(pokemonId);
        if (active.isPresent()) {
            PokemonCache row = active.get();
            if (isFresh(row.getCachedAt())) {
                log.info("event=CACHE_L2_HIT by=id key={} pokemonId={}", pokemonId, row.getPokemonId());
                return writeThrough(toDto(row));
            }
            log.info("event=CACHE_L2_MISS by=id key={} reason=stale cachedAt={}",
                    pokemonId, row.getCachedAt());
        } else {
            log.info("event=CACHE_L2_MISS by=id key={} reason=absent", pokemonId);
        }
        var existing = repository.findByPokemonId(pokemonId);
        PokemonCache saved = fetchAndSave(existing.orElseGet(PokemonCache::new), String.valueOf(pokemonId));
        return writeThrough(toDto(saved));
    }

    public PokemonDetailDto getByName(String rawName) {
        String name = normalizeName(rawName);
        Cache cache = requiredCache("pokemonByName");
        Cache.ValueWrapper hit = cache.get(name);
        if (hit != null) {
            log.debug("event=CACHE_L1_HIT by=name key={}", name);
            return (PokemonDetailDto) hit.get();
        }
        log.info("event=CACHE_L1_MISS by=name key={}", name);

        var active = repository.findByPokemonNameAndDeletedFalse(name);
        if (active.isPresent()) {
            PokemonCache row = active.get();
            if (isFresh(row.getCachedAt())) {
                log.info("event=CACHE_L2_HIT by=name key={} pokemonId={}", name, row.getPokemonId());
                return writeThrough(toDto(row));
            }
            log.info("event=CACHE_L2_MISS by=name key={} reason=stale cachedAt={}",
                    name, row.getCachedAt());
        } else {
            log.info("event=CACHE_L2_MISS by=name key={} reason=absent", name);
        }
        var existing = repository.findByPokemonName(name);
        PokemonCache saved = fetchAndSave(existing.orElseGet(PokemonCache::new), name);
        return writeThrough(toDto(saved));
    }

    private PokemonCache fetchAndSave(PokemonCache entity, String identifier) {
        boolean wasDeleted = entity.getCacheId() != null && entity.isDeleted();
        JsonNode fresh;
        try {
            fresh = pokeApiClient.fetchByIdentifier(identifier);
        } catch (PokemonNotFoundException exception) {
            markDeleted(entity);
            throw exception;
        }
        entity.setPokemonId(fresh.path("id").asInt());
        entity.setPokemonName(normalizeName(fresh.path("name").asText()));
        entity.setData(fresh);
        entity.setCachedAt(clock.instant());
        entity.setDeleted(false);
        PokemonCache saved;
        try {
            saved = repository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            saved = findConcurrentSave(entity, exception);
        }
        if (wasDeleted) {
            log.info("event=POKEMON_CACHE_RESTORED pokemonId={} name={}",
                    saved.getPokemonId(), saved.getPokemonName());
        }
        return saved;
    }

    private PokemonCache findConcurrentSave(PokemonCache entity, DataIntegrityViolationException exception) {
        Optional<PokemonCache> concurrent = Optional.empty();
        if (entity.getPokemonId() != null) {
            concurrent = repository.findByPokemonIdAndDeletedFalse(entity.getPokemonId());
        }
        if (concurrent.isEmpty() && entity.getPokemonName() != null) {
            concurrent = repository.findByPokemonNameAndDeletedFalse(entity.getPokemonName());
        }
        if (concurrent.isPresent()) {
            PokemonCache saved = concurrent.get();
            log.info("event=POKEMON_CACHE_CONCURRENT_WRITE pokemonId={} name={}",
                    saved.getPokemonId(), saved.getPokemonName());
            return saved;
        }
        throw exception;
    }

    private void markDeleted(PokemonCache entity) {
        if (entity.getCacheId() == null) {
            return;
        }
        entity.setDeleted(true);
        repository.save(entity);
        evictFromL1(entity);
        log.warn("event=POKEMON_CACHE_MARKED_DELETED pokemonId={} name={}",
                entity.getPokemonId(), entity.getPokemonName());
    }

    private void evictFromL1(PokemonCache entity) {
        if (entity.getPokemonId() != null) {
            requiredCache("pokemonById").evict(entity.getPokemonId());
        }
        if (entity.getPokemonName() != null) {
            requiredCache("pokemonByName").evict(entity.getPokemonName());
        }
    }

    private PokemonDetailDto writeThrough(PokemonDetailDto dto) {
        requiredCache("pokemonById").put(dto.pokemonId(), dto);
        requiredCache("pokemonByName").put(dto.name(), dto);
        return dto;
    }

    private PokemonDetailDto toDto(PokemonCache entity) {
        JsonNode data = entity.getData();
        JsonNode typesNode = data.path("types");
        List<String> types = new ArrayList<>();
        if (typesNode.isArray()) {
            typesNode.forEach(type -> types.add(type.path("type").path("name").asText()));
        }
        String spriteUrl = data.path("sprites").path("front_default").isNull()
                ? null : data.path("sprites").path("front_default").asText(null);
        return new PokemonDetailDto(entity.getPokemonId(), entity.getPokemonName(), spriteUrl, List.copyOf(types));
    }

    private boolean isFresh(Instant cachedAt) {
        return !cachedAt.plus(ttl).isBefore(clock.instant());
    }

    private Cache requiredCache(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            throw new IllegalStateException("Cache is not configured: " + name);
        }
        return cache;
    }

    private String normalizeName(String rawName) {
        String normalized = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Pokemon name must not be blank.");
        }
        return normalized;
    }
}
