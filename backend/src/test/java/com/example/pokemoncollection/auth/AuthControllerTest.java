package com.example.pokemoncollection.auth;

import com.example.pokemoncollection.trainer.Trainer;
import com.example.pokemoncollection.trainer.TrainerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrainerRepository trainerRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AuthSessionService authSessionService;

    @Test
    void loginTrimsUsernameBeforeParameterizedRepositoryLookup(CapturedOutput output) throws Exception {
        Trainer trainer = org.mockito.Mockito.mock(Trainer.class);
        when(trainerRepository.findByUsername("ash")).thenReturn(Optional.of(trainer));
        when(trainer.getId()).thenReturn(1L);
        when(trainer.getUsername()).thenReturn("ash");
        when(trainer.getPasswordHash()).thenReturn("stored-hash");
        when(passwordEncoder.matches("password", "stored-hash")).thenReturn(true);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  ash  \",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("ash"));

        verify(trainerRepository).findByUsername(eq("ash"));
        verify(authSessionService).login(org.mockito.ArgumentMatchers.any(), eq(1L));
        assertThat(output).contains("event=AUTH_LOGIN_SUCCESS username=ash trainerId=1")
                .doesNotContain("stored-hash");
    }

    @Test
    void logoutInvalidatesSessionAndLogsTrainer(CapturedOutput output) throws Exception {
        when(authSessionService.getCurrentTrainerId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(1L));

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        verify(authSessionService).logout(org.mockito.ArgumentMatchers.any());
        assertThat(output).contains("event=AUTH_LOGOUT trainerId=1");
    }

    @Test
    void blankCredentialsAreRejectedWithoutQueryingTheRepository(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"   \",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(trainerRepository, never()).findByUsername(org.mockito.ArgumentMatchers.anyString());
        assertThat(output).contains("event=AUTH_LOGIN_FAILED username=");
    }

    @Test
    void failedLoginDoesNotLogSubmittedPassword(CapturedOutput output) throws Exception {
        Trainer trainer = org.mockito.Mockito.mock(Trainer.class);
        when(trainerRepository.findByUsername("ash")).thenReturn(Optional.of(trainer));
        when(trainer.getPasswordHash()).thenReturn("stored-hash");
        when(passwordEncoder.matches("unique-test-password", "stored-hash")).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ash\",\"password\":\"unique-test-password\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(output).contains("event=AUTH_LOGIN_FAILED username=ash")
                .doesNotContain("unique-test-password");
    }

    @Test
    void unknownUsernameStillPerformsBcryptComparison() throws Exception {
        when(trainerRepository.findByUsername("missing")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(eq("password"), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing\",\"password\":\"password\"}"))
                .andExpect(status().isUnauthorized());

        verify(passwordEncoder).matches(eq("password"), org.mockito.ArgumentMatchers.anyString());
        verify(authSessionService, never()).login(org.mockito.ArgumentMatchers.any(), eq(1L));
    }

}
