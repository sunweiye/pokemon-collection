package com.example.pokemoncollection.pokemon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PokemonCacheRepository extends JpaRepository<PokemonCache, Long> {
    Optional<PokemonCache> findByPokemonId(Integer pokemonId);
    Optional<PokemonCache> findByPokemonIdAndDeletedFalse(Integer pokemonId);
    Optional<PokemonCache> findByPokemonName(String pokemonName);
    Optional<PokemonCache> findByPokemonNameAndDeletedFalse(String pokemonName);

    @Query("select c.cacheId from PokemonCache c where c.pokemonId = :pokemonId and c.deleted = false")
    Optional<Long> findActiveCacheIdByPokemonId(@Param("pokemonId") Integer pokemonId);
}
