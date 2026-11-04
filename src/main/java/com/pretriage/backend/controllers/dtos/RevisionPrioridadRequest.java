package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.DecisionRevisionPrioridad;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RevisionPrioridadRequest(
        @NotNull DecisionRevisionPrioridad decision,
        NivelDeGravedad prioridad,
        @Size(max = 500) String motivo) {
}
