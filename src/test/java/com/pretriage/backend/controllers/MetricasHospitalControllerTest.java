package com.pretriage.backend.controllers;

import com.pretriage.backend.config.SpringSecurityConfig;
import com.pretriage.backend.controllers.dtos.metricas.DistribucionNivelItem;
import com.pretriage.backend.controllers.dtos.metricas.MetricasHospitalResponse;
import com.pretriage.backend.controllers.dtos.metricas.SerieDiariaItem;
import com.pretriage.backend.repositories.RepoMedico;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoRecepcionistas;
import com.pretriage.backend.services.MetricasHospitalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MetricasHospitalController.class,
        properties = "spring.docker.compose.enabled=false")
@Import(SpringSecurityConfig.class)
class MetricasHospitalControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MetricasHospitalService service;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RepoRecepcionistas repoRecepcionistas;
    @MockitoBean RepoMedico repoMedico;
    @MockitoBean RepoPacientes repoPacientes;

    private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 9, 1);

    @Test
    void adminDelHospitalObtieneMetricas() throws Exception {
        when(service.obtenerMetricas("admin", 1L, DESDE, HASTA)).thenReturn(new MetricasHospitalResponse(
                1L, DESDE, HASTA, 60, 42, 18, 37.5, 70.0,
                List.of(new DistribucionNivelItem("URGENTE", 60, 100.0)),
                List.of(new DistribucionNivelItem("SIN_REVISION", 60, 100.0)),
                45, 15, 75.0,
                List.of(new SerieDiariaItem(DESDE, 60, 42, 37.5))));

        mvc.perform(get("/api/admin/hospitales/1/metricas")
                        .param("desde", "2026-09-01")
                        .param("hasta", "2026-09-01")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hospitalId").value(1))
                .andExpect(jsonPath("$.desde").value("2026-09-01"))
                .andExpect(jsonPath("$.hasta").value("2026-09-01"))
                .andExpect(jsonPath("$.ingresaronACola").value(60))
                .andExpect(jsonPath("$.esperaPromedioMinutos").value(37.5))
                .andExpect(jsonPath("$.distribucionGravedadMedico[0].nivel").value("SIN_REVISION"))
                .andExpect(jsonPath("$.serieDiaria[0].fecha").value("2026-09-01"));

        verify(service).obtenerMetricas("admin", 1L, DESDE, HASTA);
    }

    @Test
    void usuarioQueNoAdministraElHospitalRecibe403() throws Exception {
        when(service.obtenerMetricas("otro", 1L, DESDE, HASTA))
                .thenThrow(new AccessDeniedException("No administrás este hospital"));

        mvc.perform(get("/api/admin/hospitales/1/metricas")
                        .param("desde", "2026-09-01")
                        .param("hasta", "2026-09-01")
                        .with(jwt().jwt(token -> token.subject("otro"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rangoInvertidoRecibe400() throws Exception {
        LocalDate hasta = LocalDate.of(2026, 8, 1);
        when(service.obtenerMetricas("admin", 1L, DESDE, hasta))
                .thenThrow(new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta"));

        mvc.perform(get("/api/admin/hospitales/1/metricas")
                        .param("desde", "2026-09-01")
                        .param("hasta", "2026-08-01")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiereAutenticacion() throws Exception {
        mvc.perform(get("/api/admin/hospitales/1/metricas")
                        .param("desde", "2026-09-01")
                        .param("hasta", "2026-09-01"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
