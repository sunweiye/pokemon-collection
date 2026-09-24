package com.example.pokemoncollection.common.exception;

public class CollectionEntryNotFoundException extends RuntimeException {
    public CollectionEntryNotFoundException(Long entryId) {
        super("Collection entry not found: " + entryId);
    }
}
