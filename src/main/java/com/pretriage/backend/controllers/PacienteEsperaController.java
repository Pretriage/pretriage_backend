package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.EstadoConsultaPacienteDTO;
import com.pretriage.backend.services.EsperaPacienteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/paciente/consulta")
public class PacienteEsperaController {

    private final EsperaPacienteService esperaPacienteService;

    @PostMapping("/cola/pausa-manual")
    public ResponseEntity<EstadoConsultaPacienteDTO> pausarColaManualmente(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.ausentarme(jwt.getSubject()));
    }

    @PostMapping("/cola/atraso/confirmar")
    public ResponseEntity<EstadoConsultaPacienteDTO> confirmarAtraso(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.estoyAtrasado(jwt.getSubject()));
    }

    @PostMapping("/cola/atraso/renovar")
    public ResponseEntity<EstadoConsultaPacienteDTO> renovarConfirmacionAtraso(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.sigoAsistiendo(jwt.getSubject()));
    }

    @PostMapping("/cola/reincorporar")
    public ResponseEntity<EstadoConsultaPacienteDTO> reincorporarseACola(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.llegue(jwt.getSubject()));
    }

    @PostMapping("/cancelar")
    public ResponseEntity<EstadoConsultaPacienteDTO> cancelarSeleccion(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.cancelarSeleccion(jwt.getSubject()));
    }

    @GetMapping("/estado")
    public ResponseEntity<EstadoConsultaPacienteDTO> obtenerEstado(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(esperaPacienteService.obtenerEstado(jwt.getSubject()));
    }
}
