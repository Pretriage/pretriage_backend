package com.pretriage.backend.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pretriage.backend.controllers.dtos.PerfilUsuarioDTO;
import com.pretriage.backend.services.UsuarioPerfilService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class UsuarioPerfilController {

    private final UsuarioPerfilService service;

    @GetMapping("/perfil")
    public ResponseEntity<PerfilUsuarioDTO> getPerfil(
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(service.obtenerPacientePerfil(jwt.getSubject()));
    }

    @PutMapping("/perfil")
    public ResponseEntity<PerfilUsuarioDTO> updatePerfil(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PerfilUsuarioDTO perfilRequest) {
        return ResponseEntity.ok(service.actualizarPacientePerfil(jwt.getSubject(), perfilRequest));
    }
}
