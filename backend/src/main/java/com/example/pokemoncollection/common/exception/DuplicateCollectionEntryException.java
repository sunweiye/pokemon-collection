package com.example.pokemoncollection.common.exception;

public class DuplicateCollectionEntryException extends RuntimeException {
    public DuplicateCollectionEntryException() {
        super("This Pokémon is already in the collection.");
    }
}
