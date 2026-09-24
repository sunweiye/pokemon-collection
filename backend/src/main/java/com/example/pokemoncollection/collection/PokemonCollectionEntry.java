package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.pokemon.PokemonCache;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pokemon_collection_entry")
public class PokemonCollectionEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trainer_id", nullable = false)
    private Long trainerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pokemon_cache_id", nullable = false)
    private PokemonCache pokemonCache;

    @Column(name = "caught_at", nullable = false)
    private Instant caughtAt;

    protected PokemonCollectionEntry() {
    }

    public PokemonCollectionEntry(Long trainerId, PokemonCache pokemonCache, Instant caughtAt) {
        this.trainerId = trainerId;
        this.pokemonCache = pokemonCache;
        this.caughtAt = caughtAt;
    }

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public PokemonCache getPokemonCache() { return pokemonCache; }
    public Instant getCaughtAt() { return caughtAt; }
}
