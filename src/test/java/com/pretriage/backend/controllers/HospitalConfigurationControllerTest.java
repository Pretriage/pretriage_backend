package com.pretriage.backend.controllers;

import com.pretriage.backend.config.SpringSecurityConfig;
import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.ConfiguracionHospitalResponse;
import com.pretriage.backend.controllers.dtos.acceso.SesionMedicaAdminResponse;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.repositories.RepoMedico;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoRecepcionistas;
import com.pretriage.backend.services.HospitalConfigurationService;
import com.pretriage.backend.services.SesionesMedicasAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HospitalConfigurationController.class,
        properties = "spring.docker.compose.enabled=false")
@Import(SpringSecurityConfig.class)
class HospitalConfigurationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean HospitalConfigurationService service;
    @MockitoBean SesionesMedicasAdminService sesionesMedicasAdminService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RepoRecepcionistas repoRecepcionistas;
    @MockitoBean RepoMedico repoMedico;
    @MockitoBean RepoPacientes repoPacientes;

    @Test
    void deshabilitaEspecialidadDelHospitalSinSectorYDevuelveConfiguracion() throws Exception {
        when(service.deshabilitarEspecialidad("admin", 7L, 4L))
                .thenReturn(new ConfiguracionHospitalResponse(List.of(), List.of(), List.of(), List.of()));

        mvc.perform(delete("/api/admin/hospitales/7/configuracion/especialidades/4")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.especialidades").isArray())
                .andExpect(jsonPath("$.salas").isArray())
                .andExpect(jsonPath("$.sectores").isArray())
                .andExpect(jsonPath("$.salasOcupadasIds").isArray());

        verify(service).deshabilitarEspecialidad("admin", 7L, 4L);
    }

    @Test
    void salasActivasEnHospitalDevuelvenConflicto() throws Exception {
        when(service.deshabilitarEspecialidad("admin", 7L, 4L))
                .thenThrow(new ConflictoDeEstadoException("Desactivá las salas de la especialidad"));

        mvc.perform(delete("/api/admin/hospitales/7/configuracion/especialidades/4")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isConflict());
    }

    @Test
    void bajaRequiereAutenticacion() throws Exception {
        mvc.perform(delete("/api/admin/hospitales/7/configuracion/especialidades/4"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rutaAnteriorConSectorYaNoExiste() throws Exception {
        mvc.perform(delete("/api/admin/hospitales/7/configuracion/sectores/2/especialidades/4")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void listaSesionesAdministrablesDelSector() throws Exception {
        LocalDateTime inicio = LocalDateTime.of(2026, 10, 21, 10, 30);
        when(sesionesMedicasAdminService.listar("admin", 7L, 2L)).thenReturn(List.of(
                new SesionMedicaAdminResponse(9L, 5L, "Ana Pérez", EstadoSesionMedica.PAUSADA,
                        2L, "Clínica", 11L, "Sala 1", "CLINICA", "Clínica médica", inicio)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/hospitales/7/configuracion/sectores/2/sesiones")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].medicoId").value(5))
                .andExpect(jsonPath("$[0].medicoNombre").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].estado").value("PAUSADA"))
                .andExpect(jsonPath("$[0].sectorId").value(2))
                .andExpect(jsonPath("$[0].salaNombre").value("Sala 1"))
                .andExpect(jsonPath("$[0].codigoEspecialidad").value("CLINICA"));

        verify(sesionesMedicasAdminService).listar("admin", 7L, 2L);
    }

    @Test
    void cierraSesionAdministrativamenteYDevuelveEstadoFinalizada() throws Exception {
        when(sesionesMedicasAdminService.cerrar("admin", 7L, 2L, 9L))
                .thenReturn(new SesionMedicaAdminResponse(9L, 5L, "Ana Pérez", EstadoSesionMedica.FINALIZADA,
                        2L, "Clínica", 11L, "Sala 1", "CLINICA", "Clínica médica",
                        LocalDateTime.of(2026, 10, 21, 10, 30)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/hospitales/7/configuracion/sectores/2/sesiones/9/cerrar")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.estado").value("FINALIZADA"));

        verify(sesionesMedicasAdminService).cerrar("admin", 7L, 2L, 9L);
    }
}
