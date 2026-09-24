package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.auth.AuthSessionService;
import com.example.pokemoncollection.collection.dto.CollectionEntryDto;
import com.example.pokemoncollection.collection.dto.CollectionPageDto;
import com.example.pokemoncollection.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/collection")
public class CollectionController {
    private final CollectionService collectionService;
    private final AuthSessionService authSessionService;

    public CollectionController(CollectionService collectionService, AuthSessionService authSessionService) {
        this.collectionService = collectionService;
        this.authSessionService = authSessionService;
    }

    @GetMapping
    public ResponseEntity<CollectionPageDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "caughtAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            HttpServletRequest request) {
        return ResponseEntity.ok(collectionService.list(currentTrainerId(request), query.trim(),
                pageable(page, size, sort, direction)));
    }

    @PostMapping
    public ResponseEntity<CollectionEntryDto> add(@RequestBody AddCollectionRequest request,
                                                   HttpServletRequest servletRequest) {
        if (request == null || request.pokemonId() == null) {
            throw new IllegalArgumentException("pokemonId is required.");
        }
        CollectionEntryDto entry = collectionService.add(currentTrainerId(servletRequest), request.pokemonId());
        return ResponseEntity.created(URI.create("/api/collection/" + entry.entryId())).body(entry);
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> remove(@PathVariable Long entryId, HttpServletRequest request) {
        collectionService.remove(currentTrainerId(request), entryId);
        return ResponseEntity.noContent().build();
    }

    private Long currentTrainerId(HttpServletRequest request) {
        return authSessionService.getCurrentTrainerId(request)
                .orElseThrow(() -> new UnauthorizedException("Authentication is required."));
    }

    private Pageable pageable(int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative.");
        }
        if (!Set.of(6, 18, 30).contains(size)) {
            throw new IllegalArgumentException("Page size must be one of: 6, 18, 30.");
        }
        Sort.Direction sortDirection;
        try {
            sortDirection = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Sort direction must be asc or desc.");
        }
        CollectionSortOption sortOption = CollectionSortOption.fromQuery(sort);
        return PageRequest.of(page, size,
                Sort.by(sortDirection, sortOption.property())
                        .and(Sort.by(sortDirection, "id")));
    }

    public record AddCollectionRequest(Integer pokemonId) {
    }
}
