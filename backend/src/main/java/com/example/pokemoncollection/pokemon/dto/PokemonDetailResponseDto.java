package com.example.pokemoncollection.pokemon.dto;

import java.util.List;

public record PokemonDetailResponseDto(Integer pokemonId, String name, String spriteUrl,
                                       List<String> types, boolean inCollection) {
}
