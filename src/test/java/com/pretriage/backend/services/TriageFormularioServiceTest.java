package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.FormularioTriageRecepcionRequest;
import com.pretriage.backend.controllers.dtos.DolorReportadoRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriageFormularioServiceTest {
    @Mock TriageIaClient triageClient;
    @Mock ObjectMapper objectMapper;
    private TriageFormularioService service;

    @BeforeEach
    void setUp() {
        service = new TriageFormularioService(triageClient, objectMapper);
    }

    @Test
    void dolorLeveEnMejoraSinFiebreNiAlarmasEsNormalInclusoEnFallback() {
        var formulario = new FormularioTriageRecepcionRequest(
                "dolor de cabeza", List.of(), "2 horas", "mejora",
                List.of(new DolorReportadoRequest("cabeza", 1)), false,
                List.of(), List.of(), List.of(), List.of(), "", "");

        var resultado = service.resultadoFallback(formulario);

        assertEquals(2, resultado.nivelPrioridad());
    }

    @Test
    void noReduceUnDolorLeveQueNoEstaMejorando() {
        var formulario = new FormularioTriageRecepcionRequest(
                "dolor de cabeza", List.of(), "2 horas", "estable",
                List.of(new DolorReportadoRequest("cabeza", 1)), false,
                List.of(), List.of(), List.of(), List.of(), "", "");

        var resultado = service.resultadoFallback(formulario);

        assertEquals(3, resultado.nivelPrioridad());
    }

    @Test
    void variosDoloresUsanLaMayorIntensidadParaClasificar() {
        var formulario = new FormularioTriageRecepcionRequest(
                "dolores en cabeza y abdomen", List.of("nauseas"), "2 horas", "estable",
                List.of(new DolorReportadoRequest("cabeza", 2), new DolorReportadoRequest("abdomen", 8)),
                false, List.of(), List.of(), List.of(), List.of(), "", "");

        var resultado = service.resultadoFallback(formulario);

        assertEquals(8, resultado.intensidadDolor());
        assertEquals(4, resultado.nivelPrioridad());
    }
}
