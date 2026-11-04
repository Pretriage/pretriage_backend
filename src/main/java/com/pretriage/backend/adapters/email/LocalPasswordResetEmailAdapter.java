package com.pretriage.backend.adapters.email;

import com.pretriage.backend.services.ports.PasswordResetEmailPort;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(name = "pretriage.cambio-contrasenia.email.mode", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalPasswordResetEmailAdapter implements PasswordResetEmailPort {

    @Override
    public void enviarToken(String destinatario, String token, LocalDateTime fechaHoraExpiracion) {
        String expiracion = fechaHoraExpiracion.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        log.info(
                "[LOCAL] Token de restablecimiento para {}: {} (vence {}) - No se envía email real. No compartas este token.",
                destinatario, token, expiracion);
    }
}
