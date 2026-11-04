package com.pretriage.backend.services.ports;

import java.time.LocalDateTime;

public interface PasswordResetEmailPort {
    void enviarToken(String destinatario, String token, LocalDateTime fechaHoraExpiracion);
}
