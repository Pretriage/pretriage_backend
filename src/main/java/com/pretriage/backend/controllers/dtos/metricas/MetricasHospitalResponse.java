package com.pretriage.backend.controllers.dtos.metricas;

import java.time.LocalDate;
import java.util.List;

/**
 * Métricas de un hospital para el período {@code [desde, hasta]} (inclusive), ancladas en
 * {@code EntradaCola.fechaHoraIngreso}. Conteos nunca null; espera y porcentajes con un decimal
 * y null cuando su denominador es 0. Las distribuciones vienen siempre completas y en orden fijo,
 * y {@code serieDiaria} trae un elemento por cada día del rango (zero-filled).
 */
public record MetricasHospitalResponse(
        Long hospitalId,
        LocalDate desde,
        LocalDate hasta,
        long ingresaronACola,
        long pacientesAtendidos,
        long pacientesNoAtendidos,
        Double esperaPromedioMinutos,
        Double porcentajeAtendidos,
        List<DistribucionNivelItem> distribucionGravedadBot,
        List<DistribucionNivelItem> distribucionGravedadMedico,
        long pretriageRealizado,
        long pretriageNoRealizado,
        Double porcentajePretriageRealizado,
        List<SerieDiariaItem> serieDiaria) {
}
