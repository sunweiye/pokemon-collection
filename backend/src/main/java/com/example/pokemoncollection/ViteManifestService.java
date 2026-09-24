package com.example.pokemoncollection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ViteManifestService {
    private final Map<String, ViteEntry> entries;
    private final String devServerUrl;

    public ViteManifestService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${app.frontend.vite-dev-server-url:}") String devServerUrl) {
        this.devServerUrl = normalizeUrl(devServerUrl);
        if (isDevMode()) {
            entries = Map.of();
            return;
        }

        Resource resource = resourceLoader.getResource("classpath:vite/manifest.json");
        if (!resource.exists()) {
            throw new IllegalStateException("Vite manifest is missing from the classpath: vite/manifest.json");
        }
        try {
            entries = objectMapper.readValue(resource.getInputStream(),
                    new TypeReference<Map<String, ViteEntry>>() { });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the Vite manifest.", exception);
        }
    }

    public boolean isDevMode() {
        return !devServerUrl.isBlank();
    }

    public String viteClientPath() {
        return devServerUrl + "/@vite/client";
    }

    public String viteReactRefreshPath() {
        return devServerUrl + "/@react-refresh";
    }

    public String faviconPath() {
        return isDevMode() ? devServerUrl + "/assets/favicon.ico" : "/assets/favicon.ico";
    }

    public String jsPath(String entryKey) {
        if (isDevMode()) {
            return devServerUrl + "/" + entryKey;
        }
        ViteEntry entry = entry(entryKey);
        return "/" + entry.file();
    }

    public List<String> cssPaths(String entryKey) {
        if (isDevMode()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        collectCss(entryKey, paths, new HashSet<>());
        return paths.stream().map(path -> "/" + path).toList();
    }

    private static String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void collectCss(String entryKey, Set<String> paths, Set<String> visited) {
        if (!visited.add(entryKey)) {
            return;
        }
        ViteEntry current = entry(entryKey);
        paths.addAll(current.css());
        current.imports().forEach(importKey -> {
            if (entries.containsKey(importKey)) {
                collectCss(importKey, paths, visited);
            }
        });
    }

    private ViteEntry entry(String entryKey) {
        ViteEntry entry = entries.get(entryKey);
        if (entry == null) {
            throw new IllegalArgumentException("Vite entry is missing: " + entryKey);
        }
        return entry;
    }

    public record ViteEntry(String file, List<String> css, List<String> imports) {
        public ViteEntry {
            css = css == null ? Collections.emptyList() : css;
            imports = imports == null ? Collections.emptyList() : imports;
        }
    }
}
