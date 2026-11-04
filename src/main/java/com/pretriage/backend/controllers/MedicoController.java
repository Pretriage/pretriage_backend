package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.services.AtencionMedicoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class MedicoController {

    private final AtencionMedicoService atencionMedicoService;

    @GetMapping("/api/medico/asignaciones")
    public ResponseEntity<List<AsignacionMedicoDTO>> obtenerAsignaciones(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(atencionMedicoService.obtenerAsignaciones(jwt.getSubject()));
    }

    @GetMapping("/api/medico/sesiones/actual")
    public ResponseEntity<SesionMedicaActualDTO> obtenerSesionActual(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(atencionMedicoService.obtenerSesionActual(jwt.getSubject()));
    }

    @GetMapping("/api/hospitales/{hospitalId}/sectores")
    public ResponseEntity<List<SectorDTO>> obtenerSectores(
            @PathVariable Long hospitalId,
            @RequestParam String codigoEspecialidad,
            @AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();

        return ResponseEntity.ok(atencionMedicoService.obtenerSectores(hospitalId, codigoEspecialidad, auth0Id));
    }

    @GetMapping("/api/hospitales/{hospitalId}/sectores/{sectorId}/salas")
    public ResponseEntity<List<SalaDTO>> obtenerSalas(
            @PathVariable Long hospitalId,
            @PathVariable Long sectorId,
            @RequestParam String codigoEspecialidad,
            @AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();

        return ResponseEntity.ok(
                atencionMedicoService.obtenerSalas(hospitalId, sectorId, codigoEspecialidad, auth0Id));
    }

    @PostMapping("/api/medico/sesiones")
    public ResponseEntity<SesionAtencionMedicaDTO> iniciarSesion(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid IniciarSesionMedicaRequest request) {
        return ResponseEntity.ok(atencionMedicoService.iniciarSesion(
                jwt.getSubject(),
                request.getHospitalId(),
                request.getCodigoEspecialidad(),
                request.getSectorId(),
                request.getSalaId()));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/pausar")
    public ResponseEntity<SesionAtencionMedicaDTO> pausarSesion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId) {
        return ResponseEntity.ok(atencionMedicoService.pausarSesion(jwt.getSubject(), sesionId));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/reanudar")
    public ResponseEntity<SesionAtencionMedicaDTO> reanudarSesion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId) {
        return ResponseEntity.ok(atencionMedicoService.reanudarSesion(jwt.getSubject(), sesionId));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/cerrar")
    public ResponseEntity<SesionAtencionMedicaDTO> cerrarSesion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId) {
        return ResponseEntity.ok(atencionMedicoService.cerrarSesion(jwt.getSubject(), sesionId));
    }

    @GetMapping("/api/medico/sesiones/{sesionId}/pacientes-disponibles")
    public ResponseEntity<List<ConsultaLlamadaDTO>> listarPacientesDisponibles(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId// ,
    /* @RequestParam(required = false) String dni */) {
        return ResponseEntity
                .ok(atencionMedicoService.listarPacientesDisponibles(jwt.getSubject(), sesionId, null));
    }

    @GetMapping("/api/medico/atenciones")
    public ResponseEntity<List<AtencionMedicaDTO>> obtenerHistorial(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(atencionMedicoService.obtenerHistorial(jwt.getSubject()));
    }

    @GetMapping("/api/medico/pacientes/{pacienteId}/historial-clinico")
    public ResponseEntity<List<EstudioClinicoDTO>> obtenerHistorialClinico(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long pacienteId) {
        return ResponseEntity.ok(atencionMedicoService.obtenerHistorialClinico(jwt.getSubject(), pacienteId));
    }

    @GetMapping("/api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/reporte")
    public ResponseEntity<EstudioClinicoDTO> obtenerMetadataDeUnEstudioClinico(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long pacienteId,
            @PathVariable Long estudioId) {
        return ResponseEntity.ok(atencionMedicoService.obtenerEstudioClinico(jwt.getSubject(), pacienteId, estudioId));
    }

    @GetMapping("/api/medico/pacientes/{pacienteId}/ultimos-reportes")
    public ResponseEntity<List<EstudioClinicoDTO>> obtenerHistorialClinicoMasActuales(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long pacienteId,
            @RequestParam(defaultValue = "5") int limite) {
        return ResponseEntity
                .ok(atencionMedicoService.obtenerUltimosEstudiosClinicos(jwt.getSubject(), pacienteId, limite));
    }

    @GetMapping("/api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/archivo")
    public ResponseEntity<byte[]> descargarArchivoEstudioClinico(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long pacienteId,
            @PathVariable Long estudioId) {

        // 1. Delegar la lógica de negocio y descarga al servicio
        byte[] archivoBytes = atencionMedicoService.descargarArchivo(jwt.getSubject(), pacienteId, estudioId);

        // 2. Configurar las cabeceras HTTP de la respuesta
        HttpHeaders headers = new HttpHeaders();

        // 'attachment' permite la descarga automática del archivo (ej. PDFs o
        // imágenes).
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"estudio_medico_" + estudioId + "\"");

        // Define el tipo de contenido. APPLICATION_OCTET_STREAM es un genérico de bytes
        // binarios.
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        // 3. Retornar la respuesta con los bytes, las cabeceras y el estado HTTP 200 OK
        return new ResponseEntity<>(archivoBytes, headers, HttpStatus.OK);
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/llamar-proximo")
    public ResponseEntity<ConsultaLlamadaDTO> llamarProximo(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId) {
        return ResponseEntity.ok(atencionMedicoService.llamarProximo(jwt.getSubject(), sesionId));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/presente")
    public ResponseEntity<ConsultaLlamadaDTO> confirmarPresente(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId) {
        return ResponseEntity.ok(atencionMedicoService.confirmarPresente(jwt.getSubject(), sesionId, consultaId));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/ausente")
    public ResponseEntity<ConsultaLlamadaDTO> marcarAusente(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId) {
        return ResponseEntity.ok(atencionMedicoService.marcarAusente(jwt.getSubject(), sesionId, consultaId));
    }

    @GetMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/pretriaje")
    public ResponseEntity<PretriajeConsultaDTO> obtenerPretriaje(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId) {
        return ResponseEntity.ok(atencionMedicoService.obtenerPretriaje(jwt.getSubject(), sesionId, consultaId));
    }

    @PutMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/revision-prioridad")
    public ResponseEntity<PretriajeConsultaDTO> revisarPrioridad(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId,
            @RequestBody @Valid RevisionPrioridadRequest request) {
        return ResponseEntity.ok(atencionMedicoService.revisarPrioridad(
                jwt.getSubject(), sesionId, consultaId, request));
    }

    @PostMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/finalizar")
    public ResponseEntity<ConsultaLlamadaDTO> finalizarConsulta(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId) {
        return ResponseEntity.ok(atencionMedicoService.finalizarConsulta(jwt.getSubject(), sesionId, consultaId));
    }
}
