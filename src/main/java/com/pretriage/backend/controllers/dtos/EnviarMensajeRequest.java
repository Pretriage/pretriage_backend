package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record EnviarMensajeRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 4000, message = "El mensaje no puede superar los 4000 caracteres")
        String contenido,
        @Pattern(regexp = "^$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "El identificador de solicitud debe ser un UUID válido")
        String solicitudId) {
    public EnviarMensajeRequest(String contenido) { this(contenido, null); }
}
