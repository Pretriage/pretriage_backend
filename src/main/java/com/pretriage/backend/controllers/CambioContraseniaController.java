package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.CambiarContraseniaAutenticadaRequest;
import com.pretriage.backend.controllers.dtos.CambiarContraseniaRequest;
import com.pretriage.backend.controllers.dtos.SolicitarTokenCambioContraseniaRequest;
import com.pretriage.backend.controllers.dtos.SolicitarTokenCambioContraseniaResponse;
import com.pretriage.backend.services.CambioContraseniaAutenticadoService;
import com.pretriage.backend.services.CambioContraseniaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/cambio-contrasenia")
@RequiredArgsConstructor
public class CambioContraseniaController {

    private final CambioContraseniaService cambioContraseniaService;
    private final CambioContraseniaAutenticadoService cambioContraseniaAutenticadoService;

    @PostMapping("/solicitar-token")
    public ResponseEntity<SolicitarTokenCambioContraseniaResponse> solicitarToken(
            @Valid @RequestBody SolicitarTokenCambioContraseniaRequest request) {

        return ResponseEntity.ok(
                cambioContraseniaService.obtenerTokenCambioContraseña(request.getEmail()));
    }

    @GetMapping("/validar")
    public ResponseEntity<Map<String, Object>> validarToken(@RequestParam("token") String token) {
        cambioContraseniaService.validarTokenCambioContrasenia(token);
        return ResponseEntity.ok(Map.of("valido", true, "message", "Token válido"));
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> cambiarContrasenia(
            @Valid @RequestBody CambiarContraseniaRequest request) {

        cambioContraseniaService.cambiarContraseña(request.getNuevaContrasenia(), request.getToken());
        return ResponseEntity.ok(Map.of("message", "Contraseña cambiada con éxito"));
    }

    /**
     * Cambio de contraseña autenticado: requiere JWT (no está en los
     * permitAll). @Valid delega las reglas de fortaleza al marco de Bean
     * Validation: si el request es inválido, Spring responde 400 ANTES de
     * invocar al servicio, por lo que el fallo de fortaleza no cuenta como
     * intento fallido.
     */
    @PostMapping("/actual")
    public ResponseEntity<Map<String, String>> cambiarContraseniaAutenticado(
            @Valid @RequestBody CambiarContraseniaAutenticadaRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        cambioContraseniaAutenticadoService.cambiarContrasenia(jwt.getSubject(), request);
        return ResponseEntity.ok(Map.of("message", "Contraseña cambiada con éxito"));
    }
}
