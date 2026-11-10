package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.services.DiscrepanciaSintomasService;
import com.pretriage.backend.services.PacienteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SancionPacienteController {
    private final PacienteService pacienteService;
    private final DiscrepanciaSintomasService discrepanciaSintomasService;

    @GetMapping("/api/paciente/sancion")
    public ResponseEntity<EstadoSancionPacienteDTO> obtener(@AuthenticationPrincipal Jwt jwt) {
        Paciente paciente = pacienteService.obtenerPacienteConUsuarioAuthId(jwt.getSubject())
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos de paciente"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(discrepanciaSintomasService.estado(paciente));
    }
}
