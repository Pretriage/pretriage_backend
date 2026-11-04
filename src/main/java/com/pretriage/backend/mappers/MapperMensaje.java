package com.pretriage.backend.mappers;

import com.pretriage.backend.controllers.dtos.MensajeDTO;
import com.pretriage.backend.model.chat.Mensaje;

public final class MapperMensaje {
    private MapperMensaje() {
    }

    public static MensajeDTO toDTO(Mensaje mensaje) {
        return new MensajeDTO(
                mensaje.getContenido(),
                mensaje.getAutor().name(),
                mensaje.getFechaHoraEnvio());
    }
}
