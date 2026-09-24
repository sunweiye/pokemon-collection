package com.example.pokemoncollection.pokemon;

import com.example.pokemoncollection.common.exception.PokeApiUnavailableException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.resolver.DefaultAddressResolverGroup;
import reactor.netty.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Component
public class PokeApiClient {
    private static final Logger log = LoggerFactory.getLogger(PokeApiClient.class);

    private final WebClient webClient;
    private final Duration timeout;

    public PokeApiClient(WebClient.Builder builder,
                         @Value("${app.pokeapi.base-url}") String baseUrl,
                         @Value("${app.pokeapi.timeout-seconds}") long timeoutSeconds) {
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .baseUrl(baseUrl)
                .build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public JsonNode fetchByIdentifier(String identifier) {
        long startedAt = System.nanoTime();
        JsonNode body;
        try {
            body = webClient.get()
                    .uri("/pokemon/{identifier}", identifier)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout);
        } catch (WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404) {
                log.info("event=POKEAPI_FAILED identifier={} status=404 durationMs={}",
                        identifier, elapsedMs(startedAt));
                throw new PokemonNotFoundException(identifier);
            }
            log.warn("event=POKEAPI_FAILED identifier={} status={} durationMs={}",
                    identifier, status, elapsedMs(startedAt), exception);
            throw new PokeApiUnavailableException("PokéAPI is unavailable.", exception);
        } catch (Exception exception) {
            String status = isTimeout(exception) ? "TIMEOUT" : "CONNECTION_ERROR";
            log.warn("event=POKEAPI_FAILED identifier={} status={} durationMs={}",
                    identifier, status, elapsedMs(startedAt), exception);
            throw new PokeApiUnavailableException("PokéAPI is unavailable.", exception);
        }
        if (body == null) {
            PokeApiUnavailableException exception =
                    new PokeApiUnavailableException("PokéAPI returned an empty response.");
            log.warn("event=POKEAPI_FAILED identifier={} status=EMPTY_RESPONSE durationMs={}",
                    identifier, elapsedMs(startedAt), exception);
            throw exception;
        }
        log.info("event=POKEAPI_SUCCESS identifier={} status=200 durationMs={}",
                identifier, elapsedMs(startedAt));
        return body;
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
