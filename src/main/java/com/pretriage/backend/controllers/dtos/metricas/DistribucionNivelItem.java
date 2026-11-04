package com.pretriage.backend.controllers.dtos.metricas;

/**
 * Bucket de una distribución de gravedad. {@code nivel} es un valor de {@code NivelDeGravedad}
 * o {@code SIN_REVISION} (solo en la distribución del médico). {@code porcentaje} es null si no hubo ingresos.
 */
public record DistribucionNivelItem(String nivel, long cantidad, Double porcentaje) {
}
