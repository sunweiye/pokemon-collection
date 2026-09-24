package com.example.pokemoncollection.collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PokemonCollectionRepository extends JpaRepository<PokemonCollectionEntry, Long> {
    @Query(value = """
            SELECT e.id AS "entryId",
                   e.pokemon_id AS "pokemonId",
                   e.pokemon_name AS name,
                   e.data -> 'sprites' ->> 'front_default' AS "spriteUrl",
                   ARRAY(
                       SELECT t.elem -> 'type' ->> 'name'
                       FROM jsonb_array_elements(COALESCE(e.data -> 'types', '[]'::jsonb))
                                WITH ORDINALITY AS t(elem, ord)
                       ORDER BY t.ord
                   ) AS types,
                   e.caught_at AS "caughtAt"
            FROM (
                SELECT ce.id,
                       ce.trainer_id,
                       ce.caught_at,
                       pc.pokemon_id,
                       pc.pokemon_name,
                       pc.data,
                       pc.deleted
                FROM pokemon_collection_entry ce
                JOIN pokemon_cache pc ON pc.cache_id = ce.pokemon_cache_id
            ) e
            WHERE e.trainer_id = :trainerId
              AND e.deleted = FALSE
              AND (:nameQuery = '' OR e.pokemon_name LIKE '%' || :nameQuery || '%' ESCAPE '\\')
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM pokemon_collection_entry e
                    JOIN pokemon_cache c ON c.cache_id = e.pokemon_cache_id
                    WHERE e.trainer_id = :trainerId
                      AND c.deleted = FALSE
                      AND (:nameQuery = '' OR c.pokemon_name LIKE '%' || :nameQuery || '%' ESCAPE '\\')
                    """,
            nativeQuery = true)
    Page<CollectionEntryView> findEntryViews(@Param("trainerId") Long trainerId,
                                             @Param("nameQuery") String nameQuery,
                                             Pageable pageable);

    @Query(value = """
            SELECT e.id AS "entryId",
                   e.pokemon_id AS "pokemonId",
                   e.pokemon_name AS name,
                   e.data -> 'sprites' ->> 'front_default' AS "spriteUrl",
                   ARRAY(
                       SELECT t.elem -> 'type' ->> 'name'
                       FROM jsonb_array_elements(COALESCE(e.data -> 'types', '[]'::jsonb))
                                WITH ORDINALITY AS t(elem, ord)
                       ORDER BY t.ord
                   ) AS types,
                   e.caught_at AS "caughtAt"
            FROM (
                SELECT ce.id,
                       ce.trainer_id,
                       ce.caught_at,
                       pc.pokemon_id,
                       pc.pokemon_name,
                       pc.data,
                       pc.deleted
                FROM pokemon_collection_entry ce
                JOIN pokemon_cache pc ON pc.cache_id = ce.pokemon_cache_id
            ) e
            WHERE e.id = :entryId
              AND e.trainer_id = :trainerId
              AND e.deleted = FALSE
            """, nativeQuery = true)
    Optional<CollectionEntryView> findEntryView(@Param("entryId") Long entryId,
                                                @Param("trainerId") Long trainerId);

    boolean existsByTrainerIdAndPokemonCache_CacheId(Long trainerId, Long pokemonCacheId);
    boolean existsByTrainerIdAndPokemonCache_PokemonId(Long trainerId, Integer pokemonId);
    Optional<PokemonCollectionEntry> findByIdAndTrainerId(Long id, Long trainerId);
}
