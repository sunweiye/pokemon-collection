package com.example.pokemoncollection.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSessionCsrfTokenRepositoryTest {
    private final HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();

    @Test
    void storesTokenInSessionWithoutCreatingACsrfCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        assertThat(request.getSession(false)).isNotNull();
        Cookie[] cookies = response.getCookies();
        assertThat(cookies).noneMatch(cookie -> "XSRF-TOKEN".equals(cookie.getName()));
        CsrfToken loaded = repository.loadToken(request);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getToken()).isEqualTo(token.getToken());
        assertThat(loaded.getHeaderName()).isEqualTo(token.getHeaderName());
        assertThat(loaded.getParameterName()).isEqualTo(token.getParameterName());
    }
}
