package com.pretriage.backend.controllers;

import com.pretriage.backend.config.SpringSecurityConfig;
import com.pretriage.backend.services.AtencionHospitalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HospitalController.class,
        properties = "spring.docker.compose.enabled=false")
@Import(SpringSecurityConfig.class)
class HospitalControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AtencionHospitalService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void ordenarPorAusenteUsaElDefaultDistancia() throws Exception {
        when(service.buscarHospitalesCercanos(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mvc.perform(get("/api/hospitales/cercanos")
                        .param("latitud", "-34.6")
                        .param("longitud", "-58.4")
                        .param("codigoEspecialidad", "PEDIATRIA")
                        .with(jwt().jwt(token -> token.subject("auth0|Paciente"))))
                .andExpect(status().isOk());

        verify(service).buscarHospitalesCercanos(eq(-34.6), eq(-58.4), eq("PEDIATRIA"),
                eq("transporte-publico"), eq("auth0|Paciente"), eq("distancia"));
    }

    @Test
    void ordenarPorVacioUsaElDefaultDistancia() throws Exception {
        when(service.buscarHospitalesCercanos(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mvc.perform(get("/api/hospitales/cercanos")
                        .param("latitud", "-34.6")
                        .param("longitud", "-58.4")
                        .param("codigoEspecialidad", "PEDIATRIA")
                        .param("ordenarPor", "")
                        .with(jwt().jwt(token -> token.subject("auth0|Paciente"))))
                .andExpect(status().isOk());

        verify(service).buscarHospitalesCercanos(eq(-34.6), eq(-58.4), eq("PEDIATRIA"),
                eq("transporte-publico"), eq("auth0|Paciente"), eq("distancia"));
    }

    @Test
    void ordenarPorInvalidoResponde400() throws Exception {
        when(service.buscarHospitalesCercanos(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Parametro ordenarPor invalido"));

        mvc.perform(get("/api/hospitales/cercanos")
                        .param("latitud", "-34.6")
                        .param("longitud", "-58.4")
                        .param("codigoEspecialidad", "PEDIATRIA")
                        .param("ordenarPor", "valoracion-invalida")
                        .with(jwt().jwt(token -> token.subject("auth0|Paciente"))))
                .andExpect(status().isBadRequest());
    }
}
