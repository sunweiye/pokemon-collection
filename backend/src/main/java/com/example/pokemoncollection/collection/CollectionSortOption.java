package com.example.pokemoncollection.collection;

import java.util.Locale;

public enum CollectionSortOption {
    CAUGHT_AT("caught_at"),
    POKEMON_ID("pokemon_id"),
    NAME("pokemon_name");

    private final String property;

    CollectionSortOption(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static CollectionSortOption fromQuery(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "caughtat" -> CAUGHT_AT;
            case "pokemonid" -> POKEMON_ID;
            case "name" -> NAME;
            default -> throw new IllegalArgumentException("Unsupported collection sort: " + value);
        };
    }
}
