package com.pretriage.backend.controllers.dtos.metricas;

import java.time.LocalDate;

/**
 * Métricas de un día calendario del período: ingresos de ese día, cuántos de ellos fueron atendidos
 * y su espera promedio en minutos (null si ese día no hubo atendidos).
 */
public record SerieDiariaItem(LocalDate fecha, long ingresados, long atendidos, Double esperaPromedioMinutos) {
}
