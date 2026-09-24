package com.example.pokemoncollection;

import com.example.pokemoncollection.collection.PokemonCollectionRepository;
import com.example.pokemoncollection.pokemon.PokemonCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CollectionIsolationIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final MockWebServer pokeApi = startPokeApi();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PokemonCacheRepository pokemonCacheRepository;

    @Autowired
    private PokemonCollectionRepository collectionRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pokeapi.base-url", () -> pokeApi.url("/api/v2").toString());
    }

    @AfterAll
    static void stopPokeApi() throws IOException {
        pokeApi.shutdown();
    }

    @BeforeEach
    void cleanApplicationData() {
        // Keep Flyway-seeded trainers, but isolate collection/cache state between tests.
        collectionRepository.deleteAllInBatch();
        pokemonCacheRepository.deleteAllInBatch();
        clearCache("pokemonById");
        clearCache("pokemonByName");
    }

    @Test
    void completesLoginSearchAddViewLogoutFlowAndEnforcesTrainerIsolation() throws Exception {
        pokeApi.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "id": 25,
                          "name": "pikachu",
                          "sprites": {"front_default": "https://img.example/pikachu.png"},
                          "types": [{"type": {"name": "electric"}}]
                        }
                        """));

        CsrfContext preLoginCsrf = fetchCsrfContext();
        MockHttpSession initialSession = preLoginCsrf.session();
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .session(initialSession)
                        .header("X-CSRF-TOKEN", preLoginCsrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":" apple ","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("apple"))
                .andReturn();

        MockHttpSession appleSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(appleSession).isNotNull();
        assertThat(appleSession.getId()).isNotEqualTo(initialSession.getId());
        String appleCsrfHeader = fetchCsrfHeader(appleSession);
        assertThat(appleCsrfHeader).isNotEqualTo(preLoginCsrf.headerValue());

        mockMvc.perform(get("/api/pokemon/search")
                        .session(appleSession)
                        .param("query", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pokemonId").value(25))
                .andExpect(jsonPath("$.name").value("pikachu"))
                .andExpect(jsonPath("$.inCollection").value(false));

        assertThat(pokeApi.takeRequest().getPath()).isEqualTo("/api/v2/pokemon/25");

        var addResult = mockMvc.perform(post("/api/collection")
                .session(appleSession)
                        .header("X-CSRF-TOKEN", appleCsrfHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pokemonId\":25}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pokemonId").value(25))
                .andExpect(jsonPath("$.name").value("pikachu"))
                .andReturn();
        JsonNode addedEntry = objectMapper.readTree(addResult.getResponse().getContentAsString());
        long entryId = addedEntry.path("entryId").asLong();

        mockMvc.perform(get("/api/collection")
                        .session(appleSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entryId").value(entryId))
                .andExpect(jsonPath("$.content[0].pokemonId").value(25))
                .andExpect(jsonPath("$.content[0].name").value("pikachu"));

        mockMvc.perform(post("/api/auth/logout")
                        .session(appleSession)
                        .header("X-CSRF-TOKEN", appleCsrfHeader))
                .andExpect(status().isNoContent())
                .andReturn();
        mockMvc.perform(get("/api/auth/me").session(appleSession))
                .andExpect(status().isUnauthorized());

        LoginContext banana = login("banana", "password");
        MockHttpSession bananaSession = banana.session();

        mockMvc.perform(get("/api/collection").session(bananaSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(delete("/api/collection/" + entryId)
                        .session(bananaSession)
                        .header("X-CSRF-TOKEN", banana.csrfHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(post("/api/auth/logout")
                        .session(bananaSession)
                        .header("X-CSRF-TOKEN", banana.csrfHeader()))
                .andExpect(status().isNoContent());
    }

    @Test
    void collectionPostWithoutCsrfTokenIsRejected() throws Exception {
        LoginContext login = login("apple", "password");

        mockMvc.perform(post("/api/collection")
                        .session(login.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pokemonId\":25}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_FORBIDDEN"));

        assertThat(collectionRepository.count()).isZero();
    }

    @Test
    void apiRequestWithoutCookieDoesNotCreateSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void appPageCreatesSessionAndRendersCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(csrfMetaValue(result)).isNotBlank();
    }

    @Test
    void loginPageCreatesSessionAndRendersCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(csrfMetaValue(result)).isNotBlank();
    }

    @Test
    void unknownRootPathReturnsHtmlNotFound() throws Exception {
        mockMvc.perform(get("/foo"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Page not found")));
    }

    @Test
    void unknownNestedLoginPathReturnsHtmlNotFound() throws Exception {
        mockMvc.perform(get("/login/extra"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Page not found")));
    }

    @Test
    void faviconIsNotHandledAsTheApplicationPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Page not found")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("<div id=\"root\">");
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void missingAssetReturnsBareNotFound() throws Exception {
        MvcResult result = mockMvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void collectionDeleteWithoutCsrfTokenIsRejected() throws Exception {
        LoginContext login = login("apple", "password");

        mockMvc.perform(delete("/api/collection/1")
                        .session(login.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_FORBIDDEN"));
    }

    @Test
    void logoutWithoutCsrfTokenIsRejected() throws Exception {
        LoginContext login = login("apple", "password");

        mockMvc.perform(post("/api/auth/logout")
                        .session(login.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_FORBIDDEN"));

        mockMvc.perform(get("/api/auth/me").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("apple"));
    }

    private LoginContext login(String username, String password) throws Exception {
        CsrfContext csrf = fetchCsrfContext();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession authenticatedSession = (MockHttpSession) result.getRequest().getSession(false);
        return new LoginContext(authenticatedSession, fetchCsrfHeader(authenticatedSession));
    }

    private CsrfContext fetchCsrfContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        return new CsrfContext((MockHttpSession) result.getRequest().getSession(false), csrfMetaValue(result));
    }

    private String fetchCsrfHeader(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        return csrfMetaValue(result);
    }

    private String csrfMetaValue(MvcResult result) throws IOException {
        Matcher matcher = Pattern.compile("meta name=\"_csrf\" content=\"([^\"]+)\"")
                .matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new AssertionError("Expected _csrf meta tag");
        }
        return matcher.group(1);
    }

    private record LoginContext(MockHttpSession session, String csrfHeader) {
    }

    private record CsrfContext(MockHttpSession session, String headerValue) {
    }

    private record LoginRequest(String username, String password) {
    }

    private void clearCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private static MockWebServer startPokeApi() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
