package com.example.pokemoncollection.collection.dto;

import java.time.Instant;
import java.util.List;

public record CollectionEntryDto(Long entryId, Integer pokemonId, String name,
                                 String spriteUrl, List<String> types, Instant caughtAt) {
}
