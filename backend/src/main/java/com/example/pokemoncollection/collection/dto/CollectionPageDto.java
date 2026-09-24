package com.example.pokemoncollection.collection.dto;

import java.util.List;

public record CollectionPageDto(List<CollectionEntryDto> content,
                               int page,
                               int size,
                               long totalElements,
                               int totalPages) {
}
