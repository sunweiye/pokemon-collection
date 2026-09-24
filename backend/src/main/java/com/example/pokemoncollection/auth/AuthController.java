package com.example.pokemoncollection.auth;

import com.example.pokemoncollection.common.exception.UnauthorizedException;
import com.example.pokemoncollection.trainer.Trainer;
import com.example.pokemoncollection.trainer.TrainerRepository;
import com.example.pokemoncollection.trainer.dto.TrainerDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    // Run the same BCrypt work for unknown usernames so response time cannot reveal
    // whether a username exists. This is a timing-attack mitigation, not a login credential.
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$iLmKqSzNPlxFe9FgHjBhN.rOIGqaBJsIwPu8UgPkUwsjEk./YMrya";

    private final TrainerRepository trainerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    public AuthController(TrainerRepository trainerRepository, PasswordEncoder passwordEncoder,
                          AuthSessionService authSessionService) {
        this.trainerRepository = trainerRepository;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<TrainerDto> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isBlank() || password.isBlank()) {
            log.warn("event=AUTH_LOGIN_FAILED username={}", username);
            throw new UnauthorizedException("Invalid username or password.");
        }
        Trainer trainer = trainerRepository.findByUsername(username).orElse(null);
        // Always verify a password, even when no trainer was found, to keep failed login timing consistent.
        String passwordHash = trainer == null ? DUMMY_PASSWORD_HASH : trainer.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);
        if (trainer == null || !passwordMatches) {
            log.warn("event=AUTH_LOGIN_FAILED username={}", username);
            throw new UnauthorizedException("Invalid username or password.");
        }

        authSessionService.login(servletRequest, trainer.getId());
        log.info("event=AUTH_LOGIN_SUCCESS username={} trainerId={}", username, trainer.getId());
        return ResponseEntity.ok(toDto(trainer));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        Long trainerId = requireTrainer(request);
        authSessionService.logout(request);
        log.info("event=AUTH_LOGOUT trainerId={}", trainerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<TrainerDto> me(HttpServletRequest request) {
        Long trainerId = requireTrainer(request);
        Trainer trainer = trainerRepository.findById(trainerId)
                .orElseThrow(() -> new UnauthorizedException("Authentication is no longer valid."));
        return ResponseEntity.ok(toDto(trainer));
    }

    private Long requireTrainer(HttpServletRequest request) {
        return authSessionService.getCurrentTrainerId(request)
                .orElseThrow(() -> new UnauthorizedException("Authentication is required."));
    }

    private TrainerDto toDto(Trainer trainer) {
        return new TrainerDto(trainer.getId(), trainer.getUsername());
    }

    public record LoginRequest(String username, String password) {
    }
}
