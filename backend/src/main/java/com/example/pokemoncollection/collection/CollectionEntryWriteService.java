package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.collection.dto.CollectionEntryDto;
import com.example.pokemoncollection.common.exception.CollectionEntryNotFoundException;
import com.example.pokemoncollection.common.exception.DuplicateCollectionEntryException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.example.pokemoncollection.pokemon.PokemonCacheRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Owns only the short database transaction that inserts a collection entry.
 * The external PokéAPI lookup is deliberately performed by CollectionService
 * before this transaction starts.
 */
@Service
public class CollectionEntryWriteService {
    private static final String COLLECTION_UNIQUE_CONSTRAINT =
            "pokemon_collection_entry_trainer_id_pokemon_cache_id_key";
    private static final Logger log = LoggerFactory.getLogger(CollectionEntryWriteService.class);

    private final PokemonCollectionRepository collectionRepository;
    private final PokemonCacheRepository cacheRepository;

    public CollectionEntryWriteService(PokemonCollectionRepository collectionRepository,
                                       PokemonCacheRepository cacheRepository) {
        this.collectionRepository = collectionRepository;
        this.cacheRepository = cacheRepository;
    }

    @Transactional
    public CollectionEntryDto addAfterLookup(Long trainerId, Integer pokemonId) {
        Long cacheId = cacheRepository.findActiveCacheIdByPokemonId(pokemonId)
                .orElseThrow(() -> new PokemonNotFoundException(String.valueOf(pokemonId)));
        if (collectionRepository.existsByTrainerIdAndPokemonCache_CacheId(trainerId, cacheId)) {
            log.info("event=COLLECTION_ADD_DUPLICATE trainerId={} pokemonId={}", trainerId, pokemonId);
            throw new DuplicateCollectionEntryException();
        }
        PokemonCollectionEntry entry;
        try {
            entry = collectionRepository.save(
                    new PokemonCollectionEntry(trainerId, cacheRepository.getReferenceById(cacheId), Instant.now()));
            collectionRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (!isCollectionUniqueConstraintViolation(exception)) {
                throw exception;
            }
            log.info("event=COLLECTION_ADD_DUPLICATE trainerId={} pokemonId={}", trainerId, pokemonId);
            throw new DuplicateCollectionEntryException();
        }
        log.info("event=COLLECTION_ADD trainerId={} pokemonId={} entryId={}",
                trainerId, pokemonId, entry.getId());
        return collectionRepository.findEntryView(entry.getId(), trainerId)
                .map(this::toDto)
                .orElseThrow(() -> new CollectionEntryNotFoundException(entry.getId()));
    }

    private boolean isCollectionUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && COLLECTION_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private CollectionEntryDto toDto(CollectionEntryView view) {
        String[] types = view.getTypes();
        return new CollectionEntryDto(view.getEntryId(), view.getPokemonId(), view.getName(),
                view.getSpriteUrl(), types == null ? List.of() : Arrays.stream(types).toList(),
                view.getCaughtAt());
    }
}
