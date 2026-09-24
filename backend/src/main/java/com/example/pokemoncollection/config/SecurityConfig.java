package com.example.pokemoncollection.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SecurityFilterChain csrfFilterChain(HttpSecurity http,
                                               CsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler(csrfAccessDeniedHandler()));
        return http.build();
    }

    @Bean
    public AccessDeniedHandler csrfAccessDeniedHandler() {
        return (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"error\":\"CSRF_FORBIDDEN\",\"message\":\"The security token is missing or expired. Refresh the page and try again.\"}");
        };
    }
}
