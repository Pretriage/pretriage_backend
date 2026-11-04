package com.pretriage.backend.controllers.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Datos recolectados por Gemini Live al cerrar la entrevista por voz. No incluye
 * prioridad: la preclasificacion la hace Gemini a partir de este resumen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumenEntrevistaVoz(
        String motivoConsulta,
        List<String> sintomas,
        String inicio,
        String evolucion,
        Integer intensidadDolor,
        List<String> signosAlarma,
        List<String> antecedentesRelevantes,
        List<String> medicamentos,
        List<String> alergias,
        String posibilidadEmbarazo,
        String observaciones) {
}
