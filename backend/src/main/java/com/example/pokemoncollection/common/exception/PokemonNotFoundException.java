package com.example.pokemoncollection.common.exception;

public class PokemonNotFoundException extends RuntimeException {
    public PokemonNotFoundException(String identifier) {
        super("No Pokémon found for identifier: " + identifier);
    }
}
