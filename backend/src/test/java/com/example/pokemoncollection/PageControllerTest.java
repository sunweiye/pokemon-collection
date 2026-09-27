package com.example.pokemoncollection;

import com.example.pokemoncollection.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageControllerTest {
    private final ViteManifestService viteManifestService = mock(ViteManifestService.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private final PageController controller = new PageController(viteManifestService, authSessionService);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Model model = mock(Model.class);
    private final CsrfToken csrfToken = mock(CsrfToken.class);

    @Test
    void loggedInTrainerIsRedirectedFromLoginPage() {
        when(authSessionService.getCurrentTrainerId(request)).thenReturn(Optional.of(7L));

        assertThat(controller.loginPage(request, model, csrfToken)).isEqualTo("redirect:/");
        verify(viteManifestService, never()).isDevMode();
        verify(model, never()).addAttribute(any(String.class), any());
    }

    @Test
    void anonymousTrainerStillGetsLoginView() {
        when(authSessionService.getCurrentTrainerId(request)).thenReturn(Optional.empty());
        when(viteManifestService.isDevMode()).thenReturn(true);
        when(viteManifestService.viteClientPath()).thenReturn("http://localhost:5173/@vite/client");
        when(viteManifestService.viteReactRefreshPath()).thenReturn("http://localhost:5173/@react-refresh");
        when(viteManifestService.jsPath("src/login/main.tsx")).thenReturn("http://localhost:5173/src/login/main.tsx");
        when(viteManifestService.cssPaths("src/login/main.tsx")).thenReturn(List.of());

        assertThat(controller.loginPage(request, model, csrfToken)).isEqualTo("login");
        verify(model).addAttribute("csrfToken", csrfToken);
    }
}
