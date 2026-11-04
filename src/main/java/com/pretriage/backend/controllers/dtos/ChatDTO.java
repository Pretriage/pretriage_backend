package com.pretriage.backend.controllers.dtos;

import java.time.LocalDateTime;
import java.util.List;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;

public record ChatDTO(
        Long id,
        List<MensajeDTO> mensajes,
        LocalDateTime timestamp,
        boolean finalizado,
        EstadoClasificacionChat estadoClasificacion,
        EstadoTurnoTexto estadoTurnoTexto,
        String solicitudTurnoTextoId,
        int intentosTurnoTexto) {
    public ChatDTO(Long id, List<MensajeDTO> mensajes, LocalDateTime timestamp, boolean finalizado) {
        this(id, mensajes, timestamp, finalizado, EstadoClasificacionChat.NO_SOLICITADA,
                EstadoTurnoTexto.SIN_SOLICITAR, null, 0);
    }
    public ChatDTO(Long id, List<MensajeDTO> mensajes, LocalDateTime timestamp, boolean finalizado,
                   EstadoClasificacionChat estadoClasificacion) {
        this(id, mensajes, timestamp, finalizado, estadoClasificacion, EstadoTurnoTexto.SIN_SOLICITAR, null, 0);
    }
}
