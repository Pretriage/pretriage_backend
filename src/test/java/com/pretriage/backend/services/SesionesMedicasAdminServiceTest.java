package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.acceso.AuditoriaHospital;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAtencionesMedicas;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSectores;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SesionesMedicasAdminServiceTest {
    @Mock StaffAccessService staffAccessService;
    @Mock RepoSectores sectores;
    @Mock RepoSesionesAtencionMedica sesiones;
    @Mock RepoEntradasCola entradasCola;
    @Mock RepoAtencionesMedicas atenciones;
    @Mock RepoAuditoriasHospital auditorias;

    private SesionesMedicasAdminService service;
    private Hospital hospital;
    private Sector sector;
    private EspecialidadMedica especialidad;
    private UsuarioAuth actor;

    @BeforeEach
    void setUp() {
        service = new SesionesMedicasAdminService(staffAccessService, sectores, sesiones,
                entradasCola, atenciones, auditorias);
        hospital = new Hospital();
        hospital.setId(7L);
        sector = new Sector();
        sector.setId(2L);
        sector.setNombre("Clínica");
        sector.setHospital(hospital);
        especialidad = new EspecialidadMedica();
        especialidad.setId(3L);
        especialidad.setCodigo("CLINICA");
        especialidad.setNombre("Clínica médica");
        actor = new UsuarioAuth();
        actor.setId("auth0|admin");
        actor.setNombre("Admin");
        lenient().when(staffAccessService.exigirAdminHospital("admin", 7L)).thenReturn(actor);
        lenient().when(sectores.findByIdAndHospitalId(2L, 7L)).thenReturn(Optional.of(sector));
        lenient().when(sesiones.save(any(SesionAtencionMedica.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void listaSesionesActivasYPausadasDeSectorInactivoIncluyendoLegadasPorSala() {
        sector.setActiva(false);
        SesionAtencionMedica directa = sesion(9L, EstadoSesionMedica.ACTIVA);
        SesionAtencionMedica legada = sesion(10L, EstadoSesionMedica.PAUSADA);
        legada.setSector(null);
        when(sesiones.findByHospitalIdAndSectorOrLegacySalaSectorAndEstadoIn(eq(7L), eq(2L), anyCollection()))
                .thenReturn(List.of(directa, legada));

        var resultado = service.listar("admin", 7L, 2L);

        assertEquals(2, resultado.size());
        assertEquals(EstadoSesionMedica.ACTIVA, resultado.get(0).estado());
        assertEquals(EstadoSesionMedica.PAUSADA, resultado.get(1).estado());
        assertEquals(2L, resultado.get(1).sectorId());
        assertEquals("Clínica", resultado.get(1).sectorNombre());
        verify(sesiones).findByHospitalIdAndSectorOrLegacySalaSectorAndEstadoIn(eq(7L), eq(2L),
                eq(List.of(EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA)));
    }

    @Test
    void cierraSesionActivaYRegistraAuditoriaDelAdministrador() {
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.ACTIVA);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));

        var response = service.cerrar("admin", 7L, 2L, 9L);

        assertEquals(EstadoSesionMedica.FINALIZADA, response.estado());
        assertNotNull(sesion.getFechaHoraFin());
        ArgumentCaptor<AuditoriaHospital> auditoria = ArgumentCaptor.forClass(AuditoriaHospital.class);
        verify(auditorias).save(auditoria.capture());
        assertSame(hospital, auditoria.getValue().getHospital());
        assertSame(actor, auditoria.getValue().getActor());
        assertEquals("SESION_MEDICA_CERRADA_POR_ADMIN", auditoria.getValue().getAccion());
        assertEquals("sesion:9", auditoria.getValue().getObjetivo());
        assertEquals("FINALIZADA", auditoria.getValue().getResultado());
        assertNotNull(auditoria.getValue().getFecha());
    }

    @Test
    void cierraSesionPausadaLegadaConSectorDerivadoDeSala() {
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.PAUSADA);
        sesion.setSector(null);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));

        var response = service.cerrar("admin", 7L, 2L, 9L);

        assertEquals(EstadoSesionMedica.FINALIZADA, response.estado());
        assertEquals(2L, response.sectorId());
        verify(sesiones).save(sesion);
    }

    @Test
    void noCierraSesionConConsultaLlamadaOEnAtencion() {
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.ACTIVA);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));
        when(entradasCola.existsByConsultaMedicaMedicoIdAndEstadoIn(eq(5L), anyCollection())).thenReturn(true);

        assertThrows(ConflictoDeEstadoException.class, () -> service.cerrar("admin", 7L, 2L, 9L));

        verify(sesiones, never()).save(any());
        verifyNoInteractions(auditorias);
    }

    @Test
    void noCierraSesionConAtencionEnCurso() {
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.ACTIVA);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));
        when(atenciones.existsBySesionAtencionMedicaIdAndEstado(9L, EstadoAtencionMedica.EN_CURSO))
                .thenReturn(true);

        assertThrows(ConflictoDeEstadoException.class, () -> service.cerrar("admin", 7L, 2L, 9L));

        verify(sesiones, never()).save(any());
        verifyNoInteractions(auditorias);
    }

    @Test
    void cierreDeSesionFinalizadaEsIdempotenteYConservaFechaYAuditoria() {
        LocalDateTime fechaFin = LocalDateTime.of(2026, 10, 20, 11, 0);
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.FINALIZADA);
        sesion.setFechaHoraFin(fechaFin);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));

        var response = service.cerrar("admin", 7L, 2L, 9L);

        assertEquals(EstadoSesionMedica.FINALIZADA, response.estado());
        assertEquals(fechaFin, sesion.getFechaHoraFin());
        verify(sesiones, never()).save(any());
        verifyNoInteractions(auditorias, entradasCola, atenciones);
    }

    @Test
    void rechazaAdministradorDeOtroHospitalAntesDeConsultarSector() {
        doThrow(new AccessDeniedException("No administrás este hospital"))
                .when(staffAccessService).exigirAdminHospital("admin", 8L);

        assertThrows(AccessDeniedException.class, () -> service.listar("admin", 8L, 2L));

        verifyNoInteractions(sesiones);
        verify(sectores, never()).findByIdAndHospitalId(2L, 8L);
    }

    @Test
    void noCierraSesionAsignadaAOtroSector() {
        Sector otroSector = new Sector();
        otroSector.setId(4L);
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.ACTIVA);
        sesion.setSector(otroSector);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));

        assertThrows(RecursoNoEncontradoException.class, () -> service.cerrar("admin", 7L, 2L, 9L));

        verify(sesiones, never()).save(any());
        verifyNoInteractions(auditorias);
    }

    @Test
    void noCierraSesionDeOtroHospitalAunqueElSectorSolicitadoSeaPropio() {
        Hospital otroHospital = new Hospital();
        otroHospital.setId(8L);
        SesionAtencionMedica sesion = sesion(9L, EstadoSesionMedica.ACTIVA);
        sesion.setHospital(otroHospital);
        when(sesiones.findByIdForUpdate(9L)).thenReturn(Optional.of(sesion));

        assertThrows(RecursoNoEncontradoException.class, () -> service.cerrar("admin", 7L, 2L, 9L));

        verify(sesiones, never()).save(any());
        verifyNoInteractions(auditorias);
    }

    private SesionAtencionMedica sesion(Long id, EstadoSesionMedica estado) {
        UsuarioAuth usuarioMedico = new UsuarioAuth();
        usuarioMedico.setNombre("Ana");
        usuarioMedico.setApellido("Pérez");
        Medico medico = new Medico();
        medico.setId(5L);
        medico.setUsuarioAuth(usuarioMedico);
        Sala sala = new Sala();
        sala.setId(11L);
        sala.setNombre("Sala 1");
        sala.setSector(sector);
        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setId(id);
        sesion.setHospital(hospital);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(sector);
        sesion.setSala(sala);
        sesion.setMedico(medico);
        sesion.setEstado(estado);
        sesion.setFechaHoraInicio(LocalDateTime.of(2026, 10, 21, 10, 30));
        return sesion;
    }
}
