package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.common.exception.CollectionEntryNotFoundException;
import com.example.pokemoncollection.collection.dto.CollectionEntryDto;
import com.example.pokemoncollection.collection.dto.CollectionPageDto;
import com.example.pokemoncollection.pokemon.PokemonLookupService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private final PokemonCollectionRepository collectionRepository;
    private final PokemonLookupService lookupService;
    private final CollectionEntryWriteService writeService;

    public CollectionService(PokemonCollectionRepository collectionRepository,
                             PokemonLookupService lookupService,
                             CollectionEntryWriteService writeService) {
        this.collectionRepository = collectionRepository;
        this.lookupService = lookupService;
        this.writeService = writeService;
    }

    @Transactional(readOnly = true)
    public CollectionPageDto list(Long trainerId, String rawNameQuery, Pageable pageable) {
        String nameQuery = normalizeNameQuery(rawNameQuery);
        Page<CollectionEntryView> page = collectionRepository.findEntryViews(trainerId, nameQuery, pageable);
        List<CollectionEntryDto> content = page.getContent().stream()
                .map(this::toDto)
                .toList();
        Sort.Order order = pageable.getSort().stream().findFirst().orElse(null);
        String sort = order == null ? "unsorted" : order.getProperty();
        String direction = order == null ? "unspecified" : order.getDirection().name().toLowerCase(Locale.ROOT);
        log.info("event=COLLECTION_LIST trainerId={} page={} size={} query={} sort={} direction={} returned={} totalElements={}",
                trainerId, page.getNumber(), page.getSize(), nameQuery, sort, direction,
                content.size(), page.getTotalElements());
        return new CollectionPageDto(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public boolean contains(Long trainerId, Integer pokemonId) {
        return collectionRepository.existsByTrainerIdAndPokemonCache_PokemonId(trainerId, pokemonId);
    }

    public CollectionEntryDto add(Long trainerId, Integer pokemonId) {
        lookupService.getById(pokemonId);
        return writeService.addAfterLookup(trainerId, pokemonId);
    }

    @Transactional
    public void remove(Long trainerId, Long entryId) {
        PokemonCollectionEntry entry = collectionRepository.findByIdAndTrainerId(entryId, trainerId)
                .orElse(null);
        if (entry == null) {
            log.info("event=COLLECTION_REMOVE_NOT_FOUND trainerId={} entryId={}", trainerId, entryId);
            throw new CollectionEntryNotFoundException(entryId);
        }
        collectionRepository.delete(entry);
        log.info("event=COLLECTION_REMOVE trainerId={} entryId={}", trainerId, entryId);
    }

    private CollectionEntryDto toDto(CollectionEntryView view) {
        String[] types = view.getTypes();
        return new CollectionEntryDto(view.getEntryId(), view.getPokemonId(), view.getName(),
                view.getSpriteUrl(), types == null ? List.of() : Arrays.stream(types).toList(),
                view.getCaughtAt());
    }

    private String normalizeNameQuery(String rawNameQuery) {
        if (rawNameQuery == null || rawNameQuery.isBlank()) {
            return "";
        }
        return rawNameQuery.trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
