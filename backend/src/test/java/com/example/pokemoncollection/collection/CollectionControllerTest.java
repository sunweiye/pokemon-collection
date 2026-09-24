package com.example.pokemoncollection.collection;

import com.example.pokemoncollection.auth.AuthSessionService;
import com.example.pokemoncollection.collection.dto.CollectionPageDto;
import com.example.pokemoncollection.common.GlobalExceptionHandler;
import com.example.pokemoncollection.collection.dto.CollectionEntryDto;
import com.example.pokemoncollection.common.exception.CollectionEntryNotFoundException;
import com.example.pokemoncollection.common.exception.DuplicateCollectionEntryException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CollectionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CollectionService collectionService;

    @MockBean
    private AuthSessionService authSessionService;

    @Test
    void listPassesSearchPagingAndSortToTheService() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));
        when(collectionService.list(eq(7L), eq("chu"), any(Pageable.class))).thenReturn(
                new CollectionPageDto(
                        List.of(new CollectionEntryDto(10L, 25, "pikachu", "sprite", List.of("electric"),
                                Instant.parse("2026-09-24T00:00:00Z"))),
                        1, 18, 19, 2));

        mockMvc.perform(get("/api/collection")
                        .param("page", "1")
                        .param("size", "18")
                        .param("query", " chu ")
                        .param("sort", "name")
                        .param("direction", "asc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].pokemonId").value(25))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(18))
                .andExpect(jsonPath("$.totalElements").value(19));

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(collectionService).list(eq(7L), eq("chu"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(18);
        assertThat(pageable.getValue().getSort().getOrderFor("pokemon_name").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
    }

    @Test
    void invalidPageSizeIsRejected() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/collection").param("size", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(collectionService, never()).list(any(), any(), any());
    }

    @Test
    void unauthenticatedListIsRejected() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/collection"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().encoding("UTF-8"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(collectionService, never()).list(any(), any(), any());
    }

    @Test
    void malformedJsonBodyIsBadRequest() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(collectionService);
    }

    @Test
    void unauthenticatedAddIsRejected() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pokemonId\":25}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(collectionService, never()).add(any(), any());
    }

    @Test
    void nullPokemonIdIsBadRequest() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(collectionService, never()).add(any(), any());
    }

    @Test
    void missingPokemonIsMappedToNotFound() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));
        when(collectionService.add(7L, 25)).thenThrow(new PokemonNotFoundException("25"));

        mockMvc.perform(post("/api/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pokemonId\":25}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void duplicateAddIsMappedToConflict() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));
        when(collectionService.add(7L, 25)).thenThrow(new DuplicateCollectionEntryException());

        mockMvc.perform(post("/api/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pokemonId\":25}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_COLLECTION_ENTRY"));
    }

    @Test
    void unauthenticatedRemoveIsRejected() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/collection/10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(collectionService, never()).remove(any(), any());
    }

    @Test
    void removingMissingEntryIsMappedToNotFound() throws Exception {
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));
        doThrow(new CollectionEntryNotFoundException(10L)).when(collectionService).remove(7L, 10L);

        mockMvc.perform(delete("/api/collection/10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
