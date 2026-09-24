package com.example.pokemoncollection.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class HttpSessionAuthService implements AuthSessionService {
    private static final String TRAINER_ID_ATTRIBUTE = "TRAINER_ID";

    @Override
    public void login(HttpServletRequest request, Long trainerId) {
        HttpSession previousSession = request.getSession(false);
        if (previousSession != null) {
            previousSession.invalidate();
        }
        request.getSession(true).setAttribute(TRAINER_ID_ATTRIBUTE, trainerId);
    }

    @Override
    public Optional<Long> getCurrentTrainerId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Long) session.getAttribute(TRAINER_ID_ATTRIBUTE));
    }

    @Override
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
