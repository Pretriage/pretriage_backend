package com.pretriage.backend.controllers;

import com.pretriage.backend.services.TiempoEstimadoNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/atencion/tiempos")
@RequiredArgsConstructor
public class TiempoEstimadoController {
    private final TiempoEstimadoNotifier notifier;

    @GetMapping(value = "/suscribirse/{consultaId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suscribirse(@AuthenticationPrincipal Jwt jwt, @PathVariable Long consultaId) {
        return notifier.conectar(jwt.getSubject(), consultaId);
    }
}
