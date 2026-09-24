package com.example.pokemoncollection.pokemon.dto;

import java.util.List;

public record PokemonDetailDto(Integer pokemonId, String name, String spriteUrl, List<String> types) {
}
