package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.auth.AuthSessionService;
import com.example.pokemoncollection.collection.CollectionService;
import com.example.pokemoncollection.common.GlobalExceptionHandler;
import com.example.pokemoncollection.common.exception.PokeApiUnavailableException;
import com.example.pokemoncollection.pokemon.dto.PokemonDetailDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PokemonController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ExtendWith(OutputCaptureExtension.class)
class PokemonControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PokemonLookupService lookupService;

    @MockitoBean
    private CollectionService collectionService;

    @MockitoBean
    private AuthSessionService authSessionService;

    @Test
    void searchIncludesCurrentTrainersCollectionStatus() throws Exception {
        when(lookupService.getByName("pikachu"))
                .thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of("electric")));
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.of(7L));
        when(collectionService.contains(7L, 25)).thenReturn(true);

        mockMvc.perform(get("/api/pokemon/search")
                        .param("query", " pikachu ")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pokemonId").value(25))
                .andExpect(jsonPath("$.name").value("pikachu"))
                .andExpect(jsonPath("$.inCollection").value(true));

        verify(collectionService).contains(eq(7L), eq(25));
    }

    @Test
    void anonymousSearchReturnsNotInCollectionWithoutCheckingOwnership() throws Exception {
        when(lookupService.getById(25))
                .thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of("electric")));
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/pokemon/25").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inCollection").value(false));

        verify(collectionService, never()).contains(anyLong(), anyInt());
    }

    @Test
    void numericSearchQueryUsesPokemonIdLookup() throws Exception {
        when(lookupService.getById(25))
                .thenReturn(new PokemonDetailDto(25, "pikachu", "sprite", List.of("electric")));
        when(authSessionService.getCurrentTrainerId(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/pokemon/search").param("query", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pokemonId").value(25));

        verify(lookupService).getById(25);
        verify(lookupService, never()).getByName(anyString());
    }

    @Test
    void upstreamFailureUsesTheStandardErrorResponse(CapturedOutput output) throws Exception {
        when(lookupService.getByName("pikachu"))
                .thenThrow(new PokeApiUnavailableException("PokeAPI is unavailable."));

        mockMvc.perform(get("/api/pokemon/search").param("query", "pikachu"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Pokémon data service is temporarily unavailable. Please try again later."));

        assertThat(output).contains("event=REQUEST_FAILED status=502 error=UPSTREAM_UNAVAILABLE path=/api/pokemon/search")
                .doesNotContain("PokeApiUnavailableException");
    }

    @Test
    void unexpectedFailureIsInternalErrorAndLoggedWithStackTrace(CapturedOutput output) throws Exception {
        when(lookupService.getByName("pikachu")).thenThrow(new IllegalStateException("unexpected-test-failure"));

        mockMvc.perform(get("/api/pokemon/search").param("query", "pikachu"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));

        assertThat(output).contains("ERROR")
                .contains("event=REQUEST_FAILED status=500 error=INTERNAL_ERROR path=/api/pokemon/search")
                .contains("java.lang.IllegalStateException: unexpected-test-failure");
    }

    @Test
    void missingSearchQueryIsBadRequest(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/pokemon/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(lookupService);
        assertThat(output).contains("event=REQUEST_FAILED status=400 error=BAD_REQUEST path=/api/pokemon/search");
    }

    @Test
    void nonNumericPokemonPathVariableIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/pokemon/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(lookupService);
    }

    @Test
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/pokemon/search"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownApiResourceIsNotFound(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        assertThat(output).contains("event=REQUEST_FAILED status=404 error=NOT_FOUND path=/api/does-not-exist");
    }
}
