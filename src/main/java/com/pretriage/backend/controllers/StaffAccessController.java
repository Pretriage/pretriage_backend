package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.*;
import com.pretriage.backend.services.StaffAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StaffAccessController {
    private final StaffAccessService service;

    @GetMapping("/staff/me")
    public StaffMeResponse me(@ AuthenticationPrincipal Jwt jwt) {
        return service.obtenerContexto(jwt.getSubject());
    }

    @GetMapping("/admin/hospitales/{hospitalId}/personal")
    public List<PersonalResponse> personal(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable("hospitalId") Long hospitalId) {
        return service.listarPersonal(jwt.getSubject(), hospitalId);
    }

    @GetMapping("/admin/hospitales/{hospitalId}/invitaciones")
    public List<InvitacionResponse> invitaciones(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable("hospitalId") Long hospitalId) {
        return service.listarInvitaciones(jwt.getSubject(), hospitalId);
    }

    @PostMapping("/admin/hospitales/{hospitalId}/invitaciones")
    public InvitacionResponse invitar(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable("hospitalId") Long hospitalId,
                                      @Valid @RequestBody CrearInvitacionRequest request) {
        return service.crearInvitacion(jwt.getSubject(), hospitalId, request);
    }

    @DeleteMapping("/admin/hospitales/{hospitalId}/invitaciones/{invitacionId}")
    public ResponseEntity<Void> revocar(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable("hospitalId") Long hospitalId, @PathVariable("invitacionId") Long invitacionId) {
        service.revocarInvitacion(jwt.getSubject(), hospitalId, invitacionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/hospitales/{hospitalId}/invitaciones/{invitacionId}/reenviar")
    public InvitacionResponse reenviar(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable("hospitalId") Long hospitalId, @PathVariable("invitacionId") Long invitacionId) {
        return service.reenviarInvitacion(jwt.getSubject(), hospitalId, invitacionId);
    }

    @PatchMapping("/admin/hospitales/{hospitalId}/membresias/{membresiaId}")
    public MembresiaResponse actualizarEstado(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable("hospitalId") Long hospitalId, @PathVariable("membresiaId") Long membresiaId,
                                               @Valid @RequestBody ActualizarMembresiaRequest request) {
        return service.actualizarEstado(jwt.getSubject(), hospitalId, membresiaId, request);
    }

    @PutMapping("/admin/hospitales/{hospitalId}/membresias/{membresiaId}/roles")
    public MembresiaResponse actualizarRoles(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable("hospitalId") Long hospitalId, @PathVariable("membresiaId") Long membresiaId,
                                              @Valid @RequestBody ActualizarRolesRequest request) {
        return service.actualizarRoles(jwt.getSubject(), hospitalId, membresiaId, request);
    }

    @GetMapping("/admin/hospitales/{hospitalId}/auditoria")
    public List<AuditoriaResponse> auditoria(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable("hospitalId") Long hospitalId) {
        return service.listarAuditoria(jwt.getSubject(), hospitalId);
    }

    @PostMapping("/platform/hospitales/{hospitalId}/primer-admin/invitaciones")
    public InvitacionResponse primerAdmin(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable("hospitalId") Long hospitalId,
                                           @Valid @RequestBody CrearInvitacionRequest request) {
        return service.crearPrimerAdmin(jwt.getSubject(), hospitalId, request);
    }

    @GetMapping("/platform/hospitales")
    public List<HospitalPlataformaResponse> hospitalesPlataforma(
            @AuthenticationPrincipal Jwt jwt) {
        return service.listarHospitalesPlataforma(jwt.getSubject());
    }

    @GetMapping("/invitaciones/{token}/resumen")
    public InvitacionResumenResponse resumen(@PathVariable("token") String token) {
        return service.resumir(token);
    }

    @PostMapping("/invitaciones/{token}/aceptar")
    public MembresiaResponse aceptar(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable("token") String token) {
        return service.aceptar(jwt.getSubject(), token);
    }

    @PostMapping("/invitaciones/{token}/registro")
    public MembresiaResponse registrar(@PathVariable("token") String token,
                                       @Valid @RequestBody RegistrarInvitadoRequest request) {
        return service.registrarYAceptar(token, request);
    }
}
