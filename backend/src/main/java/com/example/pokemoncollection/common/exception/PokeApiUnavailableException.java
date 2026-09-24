package com.example.pokemoncollection.common.exception;

public class PokeApiUnavailableException extends RuntimeException {
    public PokeApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PokeApiUnavailableException(String message) {
        super(message);
    }
}
