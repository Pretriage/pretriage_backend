package com.pretriage.backend.controllers.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties({"origenClasificacion", "seguimientoEntrevista"})
public record TriageResultDTO(
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
        String observaciones,
        Integer nivelPrioridad,
        boolean requiereAtencionInmediata,
        String recomendacionSeguridad) {
}

