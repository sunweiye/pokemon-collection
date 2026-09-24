package com.example.pokemoncollection;

import com.example.pokemoncollection.collection.CollectionEntryView;
import com.example.pokemoncollection.collection.PokemonCollectionEntry;
import com.example.pokemoncollection.collection.PokemonCollectionRepository;
import com.example.pokemoncollection.pokemon.PokemonCache;
import com.example.pokemoncollection.pokemon.PokemonCacheRepository;
import com.example.pokemoncollection.trainer.Trainer;
import com.example.pokemoncollection.trainer.TrainerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
class DataLayerTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final TrainerRepository trainerRepository;
    private final PokemonCacheRepository pokemonCacheRepository;
    private final PokemonCollectionRepository collectionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    DataLayerTest(TrainerRepository trainerRepository, PokemonCacheRepository pokemonCacheRepository,
                  PokemonCollectionRepository collectionRepository) {
        this.trainerRepository = trainerRepository;
        this.pokemonCacheRepository = pokemonCacheRepository;
        this.collectionRepository = collectionRepository;
    }

    @Test
    void jsonbPayloadRoundTripsAndNameLookupIsExact() throws Exception {
        PokemonCache saved = pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", """
                {"id":25,"name":"pikachu","sprites":{"front_default":"sprite"}}
                """));

        PokemonCache reloaded = pokemonCacheRepository.findById(saved.getCacheId()).orElseThrow();

        assertThat(reloaded.getData().path("sprites").path("front_default").asText()).isEqualTo("sprite");
        assertThat(reloaded.isDeleted()).isFalse();
        assertThat(pokemonCacheRepository.findByPokemonNameAndDeletedFalse("pikachu")).contains(reloaded);
        assertThat(pokemonCacheRepository.findByPokemonNameAndDeletedFalse("PIKACHU")).isEmpty();
        assertThat(pokemonCacheRepository.findByPokemonNameAndDeletedFalse("pika")).isEmpty();
    }

    @Test
    void deletedRowsAreExcludedFromActiveLookup() throws Exception {
        PokemonCache saved = pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", "{}"));
        saved.setDeleted(true);
        pokemonCacheRepository.saveAndFlush(saved);

        assertThat(pokemonCacheRepository.findByPokemonIdAndDeletedFalse(25)).isEmpty();
    }

    @Test
    void pokemonBusinessColumnsAreUnique() throws Exception {
        pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", "{}"));

        assertThatThrownBy(() -> pokemonCacheRepository.saveAndFlush(cache(25, "raichu", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void collectionPairIsUniquePerTrainer() throws Exception {
        Trainer trainer = trainerRepository.saveAndFlush(new Trainer("test-trainer", "hash"));
        PokemonCache cache = pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", "{}"));
        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), cache, Instant.now()));

        assertThatThrownBy(() -> collectionRepository.saveAndFlush(
                new PokemonCollectionEntry(trainer.getId(), cache, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void collectionProjectionExtractsOnlyDisplayedJsonbFieldsAndFiltersDeletedRows() throws Exception {
        Trainer trainer = trainerRepository.saveAndFlush(new Trainer("list-trainer", "hash"));
        PokemonCache active = pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", """
                {"id":25,"name":"pikachu","sprites":{"front_default":"sprite"},
                 "types":[{"slot":1,"type":{"name":"electric"}}]}
                """));
        PokemonCache noTypes = pokemonCacheRepository.saveAndFlush(cache(26, "raichu", """
                {"id":26,"name":"raichu","sprites":{"front_default":null}}
                """));
        PokemonCache deleted = cache(27, "sandshrew", "{}");
        deleted.setDeleted(true);
        deleted = pokemonCacheRepository.saveAndFlush(deleted);

        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), active,
                Instant.parse("2026-09-24T00:00:00Z")));
        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), noTypes,
                Instant.parse("2026-09-25T00:00:00Z")));
        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), deleted, Instant.now()));

        var page = collectionRepository.findEntryViews(trainer.getId(), "pika",
                PageRequest.of(0, 6, Sort.by(Sort.Direction.ASC, "pokemon_name")));

        assertThat(page.getTotalElements()).isEqualTo(1);
        CollectionEntryView view = page.getContent().getFirst();
        assertThat(view.getPokemonId()).isEqualTo(25);
        assertThat(view.getName()).isEqualTo("pikachu");
        assertThat(view.getSpriteUrl()).isEqualTo("sprite");
        assertThat(view.getTypes()).containsExactly("electric");
        var wildcardPage = collectionRepository.findEntryViews(trainer.getId(), "pi\\%", PageRequest.of(0, 6));
        assertThat(wildcardPage.getTotalElements()).isEqualTo(0L);

        var noTypesView = collectionRepository.findEntryView(
                collectionRepository.findEntryViews(trainer.getId(), "rai", PageRequest.of(0, 6))
                        .getContent().getFirst().getEntryId(), trainer.getId()).orElseThrow();
        assertThat(noTypesView.getSpriteUrl()).isNull();
        assertThat(noTypesView.getTypes()).isEmpty();
    }

    @Test
    void collectionProjectionSupportsEachSqlSortColumnAndDirection() throws Exception {
        Trainer trainer = trainerRepository.saveAndFlush(new Trainer("sort-trainer", "hash"));
        PokemonCache pikachu = pokemonCacheRepository.saveAndFlush(cache(25, "pikachu", "{}"));
        PokemonCache bulbasaur = pokemonCacheRepository.saveAndFlush(cache(1, "bulbasaur", "{}"));
        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), pikachu,
                Instant.parse("2026-09-24T00:00:00Z")));
        collectionRepository.saveAndFlush(new PokemonCollectionEntry(trainer.getId(), bulbasaur,
                Instant.parse("2026-09-25T00:00:00Z")));

        assertThat(collectionRepository.findEntryViews(trainer.getId(), "", PageRequest.of(0, 6,
                Sort.by(Sort.Direction.ASC, "caught_at"))).getContent())
                .extracting(CollectionEntryView::getName)
                .containsExactly("pikachu", "bulbasaur");
        assertThat(collectionRepository.findEntryViews(trainer.getId(), "", PageRequest.of(0, 6,
                Sort.by(Sort.Direction.DESC, "pokemon_id"))).getContent())
                .extracting(CollectionEntryView::getPokemonId)
                .containsExactly(25, 1);
        assertThat(collectionRepository.findEntryViews(trainer.getId(), "", PageRequest.of(0, 6,
                Sort.by(Sort.Direction.ASC, "pokemon_name"))).getContent())
                .extracting(CollectionEntryView::getName)
                .containsExactly("bulbasaur", "pikachu");
        assertThat(collectionRepository.findEntryViews(trainer.getId(), "", PageRequest.of(0, 6,
                Sort.by(Sort.Direction.DESC, "pokemon_name"))).getContent())
                .extracting(CollectionEntryView::getName)
                .containsExactly("pikachu", "bulbasaur");
    }

    private PokemonCache cache(int id, String name, String json) throws Exception {
        PokemonCache cache = new PokemonCache();
        cache.setPokemonId(id);
        cache.setPokemonName(name);
        cache.setCachedAt(Instant.now());
        cache.setData(objectMapper.readTree(json));
        return cache;
    }
}
