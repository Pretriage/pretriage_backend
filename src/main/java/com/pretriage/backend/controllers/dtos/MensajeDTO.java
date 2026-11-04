package com.pretriage.backend.controllers.dtos;

import java.time.LocalDateTime;

public record MensajeDTO(String contenido, String autor, LocalDateTime fechaHoraEnvio) {
}
