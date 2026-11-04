package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.*;
import com.pretriage.backend.controllers.dtos.acceso.SesionMedicaAdminResponse;
import com.pretriage.backend.services.HospitalConfigurationService;
import com.pretriage.backend.services.SesionesMedicasAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/hospitales")
public class HospitalConfigurationController {
    private final HospitalConfigurationService service;
    private final SesionesMedicasAdminService sesionesMedicasAdminService;

    @GetMapping("/{hospitalId}/configuracion")
    public ConfiguracionHospitalResponse obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId) {
        return service.obtener(jwt.getSubject(), hospitalId);
    }

    @PostMapping("/{hospitalId}/configuracion/especialidades/{especialidadId}")
    public ConfiguracionHospitalResponse habilitarEspecialidad(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId, @PathVariable Long especialidadId) {
        return service.habilitarEspecialidad(jwt.getSubject(), hospitalId, especialidadId);
    }

    @DeleteMapping("/{hospitalId}/configuracion/especialidades/{especialidadId}")
    public ConfiguracionHospitalResponse deshabilitarEspecialidad(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId, @PathVariable Long especialidadId) {
        return service.deshabilitarEspecialidad(jwt.getSubject(), hospitalId, especialidadId);
    }

    @PostMapping("/{hospitalId}/configuracion/sectores/{sectorId}/salas")
    public SalaHospitalResponse crearSala(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @PathVariable Long sectorId, @Valid @RequestBody GuardarSalaRequest request) {
        return service.crearSala(jwt.getSubject(), hospitalId, sectorId, request);
    }

    @PutMapping("/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}")
    public SalaHospitalResponse actualizarSala(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @PathVariable Long sectorId, @PathVariable Long salaId, @Valid @RequestBody GuardarSalaRequest request) {
        return service.actualizarSala(jwt.getSubject(), hospitalId, sectorId, salaId, request);
    }

    @PatchMapping("/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}/estado")
    public SalaHospitalResponse actualizarEstadoSala(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @PathVariable Long sectorId, @PathVariable Long salaId,
            @Valid @RequestBody ActualizarEstadoSalaRequest request) {
        return service.actualizarEstadoSala(jwt.getSubject(), hospitalId, sectorId, salaId, request);
    }

    @PostMapping("/{hospitalId}/configuracion/sectores")
    public SectorHospitalResponse crearSector(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @Valid @RequestBody GuardarSectorRequest request) {
        return service.crearSector(jwt.getSubject(), hospitalId, request);
    }

    @PutMapping("/{hospitalId}/configuracion/sectores/{sectorId}")
    public SectorHospitalResponse actualizarSector(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @PathVariable Long sectorId, @Valid @RequestBody ActualizarSectorRequest request) {
        return service.actualizarSector(jwt.getSubject(), hospitalId, sectorId, request);
    }

    @DeleteMapping("/{hospitalId}/configuracion/sectores/{sectorId}")
    public ResponseEntity<Void> eliminarSector(@AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId,
            @PathVariable Long sectorId) {
        service.eliminarSector(jwt.getSubject(), hospitalId, sectorId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{hospitalId}/configuracion/sectores/{sectorId}/sesiones")
    public List<SesionMedicaAdminResponse> listarSesionesDeSector(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId, @PathVariable Long sectorId) {
        return sesionesMedicasAdminService.listar(jwt.getSubject(), hospitalId, sectorId);
    }

    @PostMapping("/{hospitalId}/configuracion/sectores/{sectorId}/sesiones/{sesionId}/cerrar")
    public SesionMedicaAdminResponse cerrarSesionDeSector(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId, @PathVariable Long sectorId, @PathVariable Long sesionId) {
        return sesionesMedicasAdminService.cerrar(jwt.getSubject(), hospitalId, sectorId, sesionId);
    }

}
