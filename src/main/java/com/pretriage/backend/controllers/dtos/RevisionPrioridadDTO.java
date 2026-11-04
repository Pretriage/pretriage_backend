package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.DecisionRevisionPrioridad;
import com.pretriage.backend.model.consultas.NivelDeGravedad;

import java.time.LocalDateTime;

public record RevisionPrioridadDTO(
        Long id,
        DecisionRevisionPrioridad decision,
        NivelDeGravedad prioridadAnterior,
        NivelDeGravedad prioridadNueva,
        String motivo,
        LocalDateTime fechaHora) {
}
