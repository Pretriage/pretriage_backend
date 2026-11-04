package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.services.AdmisionRecepcionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recepcion")
@RequiredArgsConstructor
public class AdmisionRecepcionController {
    private final AdmisionRecepcionService service;

    @GetMapping("/hospitales")
    public ResponseEntity<java.util.List<RecepcionHospitalDTO>> hospitales(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(service.obtenerHospitales(jwt.getSubject()));
    }
    @GetMapping("/sesiones/activa")
    public ResponseEntity<SesionRecepcionDTO> sesionActiva(@AuthenticationPrincipal Jwt jwt) {
        SesionRecepcionDTO sesion = service.obtenerSesionActiva(jwt.getSubject());
        return sesion == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(sesion);
    }
    @GetMapping("/pacientes/{dni}")
    public ResponseEntity<PacienteRecepcionDTO> buscarPaciente(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String dni, @RequestParam Long sesionId) {
        PacienteRecepcionDTO paciente = service.buscarPaciente(jwt.getSubject(), sesionId, dni);
        return paciente == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(paciente);
    }
    @PostMapping("/sesiones")
    public ResponseEntity<SesionRecepcionDTO> iniciarSesion(@AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid IniciarSesionRecepcionRequest request) {
        return ResponseEntity.ok(service.iniciarSesion(jwt.getSubject(), request.hospitalId()));
    }
    @PostMapping("/sesiones/{sesionId}/cerrar")
    public ResponseEntity<SesionRecepcionDTO> cerrarSesion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId) {
        return ResponseEntity.ok(service.cerrarSesion(jwt.getSubject(), sesionId));
    }
    @PostMapping("/admisiones")
    public ResponseEntity<AdmisionRecepcionDTO> crearAdmision(@AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CrearAdmisionRecepcionRequest request) {
        return ResponseEntity.ok(service.crearAdmision(jwt.getSubject(), request));
    }
    @PostMapping("/admisiones/{admisionId}/finalizar")
    public ResponseEntity<AdmisionRecepcionDTO> finalizar(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long admisionId,
            @RequestBody @Valid FormularioTriageRecepcionRequest formulario) {
        return ResponseEntity.ok(service.finalizar(jwt.getSubject(), admisionId, formulario));
    }

    @GetMapping("/admisiones/{admisionId}")
    public ResponseEntity<AdmisionRecepcionDetalleDTO> obtenerAdmision(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long admisionId) {
        return ResponseEntity.ok(service.obtenerDetalle(jwt.getSubject(), admisionId));
    }

    @GetMapping("/admisiones")
    public ResponseEntity<java.util.List<AdmisionRecepcionDetalleDTO>> listarAdmisionesAbiertas(
            @AuthenticationPrincipal Jwt jwt, @RequestParam Long sesionId) {
        return ResponseEntity.ok(service.listarAbiertas(jwt.getSubject(), sesionId));
    }

    @PostMapping("/admisiones/{admisionId}/cancelar")
    public ResponseEntity<AdmisionRecepcionDetalleDTO> cancelar(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long admisionId) {
        return ResponseEntity.ok(service.cancelar(jwt.getSubject(), admisionId));
    }
}
