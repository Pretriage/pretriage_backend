package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.acceso.SesionMedicaAdminResponse;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.acceso.AuditoriaHospital;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAtencionesMedicas;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSectores;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SesionesMedicasAdminService {
    private static final List<EstadoSesionMedica> SESIONES_GESTIONABLES = List.of(
            EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA);
    private static final List<EstadoEntradaCola> CONSULTAS_TOMADAS = List.of(
            EstadoEntradaCola.LLAMADO, EstadoEntradaCola.EN_ATENCION);

    private final StaffAccessService staffAccessService;
    private final RepoSectores sectores;
    private final RepoSesionesAtencionMedica sesiones;
    private final RepoEntradasCola entradasCola;
    private final RepoAtencionesMedicas atenciones;
    private final RepoAuditoriasHospital auditorias;

    @Transactional(readOnly = true)
    public List<SesionMedicaAdminResponse> listar(String subject, Long hospitalId, Long sectorId) {
        staffAccessService.exigirAdminHospital(subject, hospitalId);
        sector(hospitalId, sectorId);
        return sesiones.findByHospitalIdAndSectorOrLegacySalaSectorAndEstadoIn(
                        hospitalId, sectorId, SESIONES_GESTIONABLES)
                .stream()
                .map(this::aResponse)
                .toList();
    }

    @Transactional
    public SesionMedicaAdminResponse cerrar(String subject, Long hospitalId, Long sectorId, Long sesionId) {
        UsuarioAuth actor = staffAccessService.exigirAdminHospital(subject, hospitalId);
        Sector sector = sector(hospitalId, sectorId);
        SesionAtencionMedica sesion = sesiones.findByIdForUpdate(sesionId)
                .filter(item -> item.getHospital() != null && hospitalId.equals(item.getHospital().getId()))
                .filter(item -> perteneceASector(item, sectorId))
                .orElseThrow(() -> new RecursoNoEncontradoException("Sesión médica no encontrada"));

        if (sesion.getEstado() == EstadoSesionMedica.FINALIZADA) {
            return aResponse(sesion);
        }
        if (!SESIONES_GESTIONABLES.contains(sesion.getEstado())) {
            throw new ConflictoDeEstadoException("Solo se pueden cerrar sesiones activas o pausadas");
        }
        if (entradasCola.existsByConsultaMedicaMedicoIdAndEstadoIn(
                    sesion.getMedico().getId(), CONSULTAS_TOMADAS)
                || atenciones.existsBySesionAtencionMedicaIdAndEstado(
                    sesionId, EstadoAtencionMedica.EN_CURSO)) {
            throw new ConflictoDeEstadoException(
                    "No se puede cerrar la sesión mientras tenga una consulta llamada o en atención");
        }

        sesion.setEstado(EstadoSesionMedica.FINALIZADA);
        sesion.setFechaHoraFin(LocalDateTime.now());
        sesion = sesiones.save(sesion);
        auditar(sector, actor, sesionId);
        return aResponse(sesion);
    }

    private Sector sector(Long hospitalId, Long sectorId) {
        return sectores.findByIdAndHospitalId(sectorId, hospitalId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Sector no encontrado"));
    }

    private boolean perteneceASector(SesionAtencionMedica sesion, Long sectorId) {
        if (sesion.getSector() != null) {
            return sesion.getSector().getId() != null && sesion.getSector().getId().equals(sectorId);
        }
        Sala sala = sesion.getSala();
        return sala != null && sala.getSector() != null
                && sectorId.equals(sala.getSector().getId());
    }

    private SesionMedicaAdminResponse aResponse(SesionAtencionMedica sesion) {
        Sector sector = sesion.getSector() != null
                ? sesion.getSector()
                : sesion.getSala() == null ? null : sesion.getSala().getSector();
        Sala sala = sesion.getSala();
        String medicoNombre = Stream.of(
                        sesion.getMedico().getUsuarioAuth().getNombre(),
                        sesion.getMedico().getUsuarioAuth().getApellido())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        return new SesionMedicaAdminResponse(
                sesion.getId(),
                sesion.getMedico().getId(),
                medicoNombre,
                sesion.getEstado(),
                sector == null ? null : sector.getId(),
                sector == null ? null : sector.getNombre(),
                sala == null ? null : sala.getId(),
                sala == null ? null : sala.getNombre(),
                sesion.getEspecialidad().getCodigo(),
                sesion.getEspecialidad().getNombre(),
                sesion.getFechaHoraInicio());
    }

    private void auditar(Sector sector, UsuarioAuth actor, Long sesionId) {
        AuditoriaHospital auditoria = new AuditoriaHospital();
        auditoria.setHospital(sector.getHospital());
        auditoria.setActor(actor);
        auditoria.setFecha(Instant.now());
        auditoria.setAccion("SESION_MEDICA_CERRADA_POR_ADMIN");
        auditoria.setObjetivo("sesion:" + sesionId);
        auditoria.setResultado("FINALIZADA");
        auditorias.save(auditoria);
    }
}
