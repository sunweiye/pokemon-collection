package com.example.pokemoncollection.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

public interface AuthSessionService {
    void login(HttpServletRequest request, Long trainerId);
    Optional<Long> getCurrentTrainerId(HttpServletRequest request);
    void logout(HttpServletRequest request);
}
