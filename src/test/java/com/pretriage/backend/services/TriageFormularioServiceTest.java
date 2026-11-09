package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.FormularioTriageRecepcionRequest;
import com.pretriage.backend.controllers.dtos.DolorReportadoRequest;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void reconoceAlarmaAfirmadaEnElRelatoSinCamposEspeciales() {
        var formulario = relato("Refiere dificultad para respirar", List.of());

        var resultado = service.resultadoFallback(formulario);

        assertEquals(4, resultado.nivelPrioridad());
        assertTrue(resultado.requiereAtencionInmediata());
        assertEquals(List.of(formulario.motivoConsulta()), resultado.signosAlarma());
    }

    @Test
    void noPromueveNegacionesYConservaLaIntensidadNarrada() {
        var formulario = relato("Dolor abdominal 8/10 y dolor de cabeza 3/10", List.of("Niega dificultad para respirar"));

        var resultado = service.resultadoFallback(formulario);

        assertEquals(4, resultado.nivelPrioridad());
        assertEquals(8, resultado.intensidadDolor());
        assertFalse(resultado.requiereAtencionInmediata());
        assertTrue(resultado.signosAlarma().isEmpty());
    }

    @Test
    void conservaLaAlarmaYLaIntensidadQueLaIaExtraeDelRelato() {
        var formulario = relato("Dolor de cabeza de ocho sobre diez", List.of("Dificultad para respirar"));
        when(objectMapper.writeValueAsString(formulario)).thenReturn("{}");
        when(triageClient.clasificarResultado(anyString(), eq("{}"))).thenReturn(new TriageResultDTO(
                "Dolor de cabeza", List.of("dolor de cabeza"), "2 horas", "estable", 8,
                List.of("Dificultad para respirar"), List.of(), List.of(), List.of(), "", "", 4, true, "Requiere evaluación"));

        var resultado = service.clasificar(formulario);

        assertEquals(4, resultado.nivelPrioridad());
        assertEquals(8, resultado.intensidadDolor());
        assertTrue(resultado.requiereAtencionInmediata());
        assertEquals(List.of("Dificultad para respirar"), resultado.signosAlarma());
    }

    @Test
    void fallbackDistingueUnaCitaDelPacienteDeAntecedentesOReferenciasATerceros() {
        var sinAlarmaActual = relato("Tuvo pérdida de conocimiento hace un año. Consulta por tos.",
                List.of("El padre tuvo un desmayo ayer.", "Niega dolor de pecho, dificultad para respirar."));
        var alarmaActual = relato("El paciente dijo «no puedo respirar».", List.of());

        assertTrue(service.resultadoFallback(sinAlarmaActual).signosAlarma().isEmpty());
        assertEquals(List.of("no puedo respirar"), service.resultadoFallback(alarmaActual).signosAlarma());
        assertEquals(4, service.resultadoFallback(relato("Refiere dolor de pecho desde hace un mes", List.of())).nivelPrioridad());
    }

    private FormularioTriageRecepcionRequest relato(String motivo, List<String> sintomas) {
        return new FormularioTriageRecepcionRequest(motivo, sintomas, "2 horas", "estable",
                List.of(), null, null, List.of(), List.of(), List.of(), "", "");
    }
}
