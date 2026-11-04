package com.pretriage.backend.controllers.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record FormularioTriageRecepcionRequest(
        @NotBlank String motivoConsulta,
        @NotNull List<String> sintomas,
        @NotBlank String inicio,
        @NotBlank String evolucion,
        @NotNull List<@Valid DolorReportadoRequest> dolores,
        Boolean fiebre,
        List<String> signosAlarma,
        List<String> antecedentesRelevantes,
        List<String> medicamentos,
        List<String> alergias,
        String posibilidadEmbarazo,
        String observaciones) {}
