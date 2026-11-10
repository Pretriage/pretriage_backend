package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.DiscrepanciaAdminDTO;
import com.pretriage.backend.services.DiscrepanciaSintomasService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/hospitales/{hospitalId}/discrepancias")
public class DiscrepanciasAdminController {
    private final DiscrepanciaSintomasService discrepanciaSintomasService;

    @GetMapping
    public ResponseEntity<List<DiscrepanciaAdminDTO>> listar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long hospitalId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(discrepanciaSintomasService.listar(jwt.getSubject(), hospitalId));
    }

    @PostMapping("/{discrepanciaId}/anular")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anular(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId, @PathVariable Long discrepanciaId) {
        discrepanciaSintomasService.anular(jwt.getSubject(), hospitalId, discrepanciaId);
    }
}
