package com.pretriage.backend.controllers.dtos;

public record ChatTurnResponse(
        MensajeDTO respuesta,
        TiempoEstimadoAtencionResponse atencionEstimada,
        String origenRespuesta) {
}
