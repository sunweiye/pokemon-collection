package com.example.pokemoncollection.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSessionAuthServiceTest {
    private final HttpSessionAuthService service = new HttpSessionAuthService();

    @Test
    void loginInvalidatesThePreviousSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("old", "value");

        service.login(request, 42L);

        assertThat(request.getSession(false).getAttribute("TRAINER_ID")).isEqualTo(42L);
        assertThat(request.getSession(false).getAttribute("old")).isNull();
    }

    @Test
    void missingSessionIsUnauthenticatedAndLogoutIsSafe() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service.getCurrentTrainerId(request)).isEmpty();
        service.logout(request);
        assertThat(service.getCurrentTrainerId(request)).isEmpty();
    }
}
