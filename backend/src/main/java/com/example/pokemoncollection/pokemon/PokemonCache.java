package com.example.pokemoncollection.pokemon;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "pokemon_cache")
public class PokemonCache {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cache_id")
    private Long cacheId;

    @Column(name = "pokemon_id", nullable = false, unique = true)
    private Integer pokemonId;

    @Column(name = "pokemon_name", nullable = false, unique = true, length = 100)
    private String pokemonName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private JsonNode data;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    public PokemonCache() {
    }

    public Long getCacheId() { return cacheId; }
    public Integer getPokemonId() { return pokemonId; }
    public String getPokemonName() { return pokemonName; }
    public JsonNode getData() { return data; }
    public Instant getCachedAt() { return cachedAt; }
    public boolean isDeleted() { return deleted; }

    void setCacheId(Long cacheId) { this.cacheId = cacheId; }
    public void setPokemonId(Integer pokemonId) { this.pokemonId = pokemonId; }
    public void setPokemonName(String pokemonName) { this.pokemonName = pokemonName; }
    public void setData(JsonNode data) { this.data = data; }
    public void setCachedAt(Instant cachedAt) { this.cachedAt = cachedAt; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
