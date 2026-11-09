package com.pretriage.backend.controllers;

import com.pretriage.backend.config.SpringSecurityConfig;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.ActualizarPerfilStaffRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.StaffMeResponse;
import com.pretriage.backend.services.StaffAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffAccessController.class)
@Import(SpringSecurityConfig.class)
class StaffAccessControllerTest {
    private static final String PERFIL = "/api/staff/me";
    private static final String SUBJECT = "auth0|staff";

    @Autowired MockMvc mockMvc;
    @MockitoBean StaffAccessService service;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void modificaElPerfilIdentificadoPorElJwt() throws Exception {
        prepararJwt();
        when(service.actualizarPerfil(SUBJECT, new ActualizarPerfilStaffRequest("Ana", "Pérez")))
                .thenReturn(new StaffMeResponse(SUBJECT, "Ana", "Pérez", "ana@example.com", false, List.of()));

        mockMvc.perform(put(PERFIL)
                        .header("Authorization", "Bearer staff-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","apellido":"Pérez","id":"auth0|otra-cuenta"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SUBJECT))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.apellido").value("Pérez"));
    }

    @Test
    void rechazaNombresInvalidosSinModificarLaCuenta() throws Exception {
        prepararJwt();
        mockMvc.perform(put(PERFIL)
                        .header("Authorization", "Bearer staff-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"   \",\"apellido\":\"" + "a".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.nombre").exists())
                .andExpect(jsonPath("$.apellido").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rechazaLaActualizacionSinSesion() throws Exception {
        mockMvc.perform(put(PERFIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","apellido":"Pérez"}
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private void prepararJwt() {
        when(jwtDecoder.decode("staff-token")).thenReturn(
                Jwt.withTokenValue("staff-token").header("alg", "RS256").subject(SUBJECT).build());
    }
}
