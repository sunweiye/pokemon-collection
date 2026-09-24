package com.example.pokemoncollection;

import com.example.pokemoncollection.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    private final ViteManifestService viteManifestService;
    private final AuthSessionService authSessionService;

    public PageController(ViteManifestService viteManifestService, AuthSessionService authSessionService) {
        this.viteManifestService = viteManifestService;
        this.authSessionService = authSessionService;
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, Model model, CsrfToken csrfToken) {
        if (authSessionService.getCurrentTrainerId(request).isPresent()) {
            return "redirect:/";
        }
        addShellAssets(model, csrfToken, "src/login/main.tsx");
        return "login";
    }

    @GetMapping("/")
    public String appPage(Model model, CsrfToken csrfToken) {
        addShellAssets(model, csrfToken, "src/app/main.tsx");
        return "index";
    }

    private void addShellAssets(Model model, CsrfToken csrfToken, String entry) {
        model.addAttribute("csrfToken", csrfToken);
        model.addAttribute("viteDevMode", viteManifestService.isDevMode());
        model.addAttribute("viteClientPath", viteManifestService.viteClientPath());
        model.addAttribute("viteReactRefreshPath", viteManifestService.viteReactRefreshPath());
        model.addAttribute("faviconPath", viteManifestService.faviconPath());
        model.addAttribute("jsPath", viteManifestService.jsPath(entry));
        model.addAttribute("cssPaths", viteManifestService.cssPaths(entry));
    }
}
