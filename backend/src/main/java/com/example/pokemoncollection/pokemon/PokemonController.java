package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.auth.AuthSessionService;
import com.example.pokemoncollection.collection.CollectionService;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailDto;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pokemon")
public class PokemonController {
    private final PokemonLookupService lookupService;
    private final CollectionService collectionService;
    private final AuthSessionService authSessionService;

    public PokemonController(PokemonLookupService lookupService, CollectionService collectionService,
                             AuthSessionService authSessionService) {
        this.lookupService = lookupService;
        this.collectionService = collectionService;
        this.authSessionService = authSessionService;
    }

    @GetMapping("/search")
    public ResponseEntity<PokemonDetailResponseDto> search(@RequestParam String query,
                                                            HttpServletRequest request) {
        String normalized = query.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank.");
        }
        if (normalized.matches("\\d+")) {
            return ResponseEntity.ok(toResponse(lookupService.getById(Integer.valueOf(normalized)), request));
        }
        return ResponseEntity.ok(toResponse(lookupService.getByName(normalized), request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PokemonDetailResponseDto> getById(@PathVariable Integer id,
                                                             HttpServletRequest request) {
        return ResponseEntity.ok(toResponse(lookupService.getById(id), request));
    }

    private PokemonDetailResponseDto toResponse(PokemonDetailDto pokemon, HttpServletRequest request) {
        boolean inCollection = authSessionService.getCurrentTrainerId(request)
                .map(trainerId -> collectionService.contains(trainerId, pokemon.pokemonId()))
                .orElse(false);
        return new PokemonDetailResponseDto(pokemon.pokemonId(), pokemon.name(), pokemon.spriteUrl(),
                pokemon.types(), inCollection);
    }
}
