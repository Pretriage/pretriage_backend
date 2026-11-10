package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.DiscrepanciaSintomasDTO;
import com.pretriage.backend.services.DiscrepanciaSintomasService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/discrepancia-sintomas")
public class DiscrepanciaSintomasController {
    private final DiscrepanciaSintomasService discrepanciaSintomasService;

    @GetMapping
    public DiscrepanciaSintomasDTO obtener(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long sesionId, @PathVariable Long consultaId) {
        return discrepanciaSintomasService.obtener(jwt.getSubject(), sesionId, consultaId);
    }

    @PutMapping
    public DiscrepanciaSintomasDTO registrar(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId) {
        return discrepanciaSintomasService.registrar(jwt.getSubject(), sesionId, consultaId);
    }
}
