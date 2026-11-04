package com.pretriage.backend.mappers;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.model.chat.Chat;

public final class MapperChat {
    private MapperChat() {
    }

    public static ChatDTO toDTO(Chat chat) {
        return new ChatDTO(
                chat.getId(),
                chat.getMensajes().stream().map(MapperMensaje::toDTO).toList(),
                chat.getFechaHoraCreacion(),
                chat.isFinalizado(), chat.getEstadoClasificacion(), chat.getEstadoTurnoTexto(),
                chat.getSolicitudTurnoTextoId(), chat.getIntentosTurnoTexto());
    }
}
