package com.example.pokemoncollection.collection;

import java.time.Instant;

public interface CollectionEntryView {
    Long getEntryId();

    Integer getPokemonId();

    String getName();

    String getSpriteUrl();

    String[] getTypes();

    Instant getCaughtAt();
}
