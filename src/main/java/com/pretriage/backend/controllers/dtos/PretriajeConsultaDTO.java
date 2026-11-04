package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoRevisionPrioridad;
import com.pretriage.backend.model.consultas.NivelDeGravedad;

public record PretriajeConsultaDTO(
        Long consultaId,
        NivelDeGravedad prioridadPreliminar,
        NivelDeGravedad prioridadEfectiva,
        EstadoRevisionPrioridad estadoRevision,
        TriageResultDTO resumenClinico,
        RevisionPrioridadDTO revisionActual) {
}
