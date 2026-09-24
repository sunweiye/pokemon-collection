package com.example.pokemoncollection.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.pokemon-cache")
public class PokemonCacheProperties {
    @Min(value = 1, message = "app.pokemon-cache.ttl-days must be at least 1")
    private int ttlDays = 7;

    public int getTtlDays() { return ttlDays; }
    public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
}
