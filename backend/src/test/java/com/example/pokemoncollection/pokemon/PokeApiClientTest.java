package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.common.exception.PokeApiUnavailableException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class PokeApiClientTest {
    private MockWebServer server;
    private PokeApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new PokeApiClient(WebClient.builder(), server.url("/api/v2").toString(), 2);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesSuccessfulJson(CapturedOutput output) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":25}"));

        assertThat(client.fetchByIdentifier("25").path("id").asInt()).isEqualTo(25);
        assertThat(output).containsPattern("event=POKEAPI_SUCCESS identifier=25 status=200 durationMs=\\d+");
    }

    @Test
    void maps404ToNotFound(CapturedOutput output) {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> client.fetchByIdentifier("missing")).isInstanceOf(PokemonNotFoundException.class);
        assertThat(output).containsPattern("event=POKEAPI_FAILED identifier=missing status=404 durationMs=\\d+");
    }

    @Test
    void maps500ToUnavailable(CapturedOutput output) {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.fetchByIdentifier("25")).isInstanceOf(PokeApiUnavailableException.class);
        assertThat(output).containsPattern("event=POKEAPI_FAILED identifier=25 status=500 durationMs=\\d+");
    }

    @Test
    void mapsTimeoutToUnavailableAndLogsTimeout(CapturedOutput output) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBodyDelay(3, TimeUnit.SECONDS)
                .setBody("{\"id\":25}"));

        assertThatThrownBy(() -> client.fetchByIdentifier("25"))
                .isInstanceOf(PokeApiUnavailableException.class);
        assertThat(output).containsPattern("event=POKEAPI_FAILED identifier=25 status=TIMEOUT durationMs=\\d+");
    }
}
