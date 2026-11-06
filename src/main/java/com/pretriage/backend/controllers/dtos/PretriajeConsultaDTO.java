package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoRevisionPrioridad;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import java.util.List;

public record PretriajeConsultaDTO(
        Long consultaId,
        NivelDeGravedad prioridadPreliminar,
        NivelDeGravedad prioridadEfectiva,
        EstadoRevisionPrioridad estadoRevision,
        TriageResultDTO resumenClinico,
        RevisionPrioridadDTO revisionActual,
        EstadoClasificacionChat estadoClasificacion,
        boolean cierreParcial,
        List<MensajeDTO> mensajesConversacion) {
}
