package com.pretriage.backend.repositories.projections;

import com.pretriage.backend.model.consultas.NivelDeGravedad;

/**
 * Cantidad de consultas agrupadas por nivel de gravedad. {@code nivel} es null para las
 * consultas sin nivel asignado (p. ej. sin revisión médica).
 */
public record ConteoPorNivelProjection(NivelDeGravedad nivel, Long cantidad) {
}
