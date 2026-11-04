package com.pretriage.backend.repositories.projections;

import java.time.LocalDateTime;

/**
 * Atención médica finalizada de un paciente del embudo de métricas: ingreso a la cola
 * ({@code EntradaCola.fechaHoraIngreso}) e inicio/fin de la atención.
 */
public record AtencionEmbudoProjection(
        LocalDateTime fechaHoraIngreso,
        LocalDateTime fechaHoraInicio,
        LocalDateTime fechaHoraFin) {
}
