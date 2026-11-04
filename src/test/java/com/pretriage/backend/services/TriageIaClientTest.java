package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.exceptions.ProveedorIaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

class TriageIaClientTest {
    @Test
    void continuacionGeneraSoloPreguntaSinCamposDeCierreOClasificacion() {
        var mapper = new ObjectMapper();
        var resultado = new com.pretriage.backend.controllers.dtos.TriageResultDTO(
                "fiebre", java.util.List.of("fiebre"), "anoche", "igual", null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                "no informado", "no informado", 3, false, "Consultar si empeora");
        var plazo = java.time.Duration.ofSeconds(3);
        when(geminiClient.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"Tenes dificultad para respirar?\",\"estadoEntrada\":\"RELEVANTE\"}",
                        "{\"valido\":true}");
        var respuesta = client.consultarContinuacion("sistema",
                "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"Me cuesta respirar\"}]}", plazo);
        assertFalse(respuesta.finalizado());
        assertEquals(null, respuesta.resultado());
        assertEquals(com.pretriage.backend.controllers.dtos.EstadoEntradaChat.RELEVANTE, respuesta.estadoEntrada());
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schema = ArgumentCaptor.forClass(Map.class);
        verify(geminiClient, times(2)).generar(anyString(), anyString(), schema.capture(), any());
        var properties = (Map<?, ?>) schema.getAllValues().get(0).get("properties");
        assertEquals(java.util.Set.of("mensaje", "estadoEntrada"), properties.keySet());
        assertEquals(java.util.Set.of("valido", "motivo"),
                ((Map<?, ?>) schema.getAllValues().get(1).get("properties")).keySet());
        Map<?, ?> questionVerifierProperties =
                (Map<?, ?>) schema.getAllValues().get(1).get("properties");
        assertFalse(questionVerifierProperties.get("motivo").toString().contains("OMISION_INICIO"));
        clearInvocations(geminiClient);
        when(geminiClient.generar(anyString(), anyString(), any(), any()))
                .thenReturn(mapper.writeValueAsString(new TriageAiResponse(true, "Listo", resultado)));
        assertThrows(ProveedorIaException.class, () -> client.consultarContinuacion("sistema",
                "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"Me cuesta respirar\"}]}", plazo));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any(), any());
    }

    private GeminiJsonClient geminiClient;
    private TriageIaClient client;

    @BeforeEach
    void setUp() {
        geminiClient = mock(GeminiJsonClient.class);
        client = new TriageIaClient(geminiClient, new ObjectMapper());
    }

    @Test
    void aceptaResultadoIntermedioConCamposNumericosNulos() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn("""
                        {
                          "finalizado": false,
                          "mensaje": "¿Desde cuando comenzaron los sintomas?",
                          "estadoEntrada": "RELEVANTE",
                          "evidenciaClinica": [{"campo":"sintomas","valor":"dolor de garganta","cita":"dolor de garganta"}],
                          "resultado": {
                            "motivoConsulta": "dolor de garganta",
                            "sintomas": ["dolor de garganta"],
                            "inicio": "ayer",
                            "evolucion": "sin cambios",
                            "intensidadDolor": null,
                            "signosAlarma": [],
                            "antecedentesRelevantes": [],
                            "medicamentos": [],
                            "alergias": [],
                            "posibilidadEmbarazo": "no informado",
                            "observaciones": "no informado",
                            "nivelPrioridad": null,
                            "requiereAtencionInmediata": false,
                            "recomendacionSeguridad": "Si empeora, consulte."
                          }
                        }
                        """);
        clearInvocations(geminiClient);

        TriageAiResponse response = client.consultar("sistema", input());

        assertFalse(response.finalizado());
        assertEquals(null, response.resultado().nivelPrioridad());
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void aceptaRecomendacionVaciaSoloDuranteEntrevistaNoUrgenteSinInventarTexto() {
        for (String recomendacion : java.util.List.of("", " ")) {
            String raw = response(false, "null", "null", "[\"dolor\"]")
                    .replace("Consulte si empeora.", recomendacion);
            when(geminiClient.generar(anyString(), anyString(), any())).thenReturn(raw);

            var result = client.consultar("sistema", input());

            assertFalse(result.finalizado());
            assertEquals(recomendacion, result.resultado().recomendacionSeguridad());
            assertEquals(null, result.resultado().nivelPrioridad());
        }
    }

    @Test
    void observacionesVaciasSonValidasSinNotasAdicionalesInclusoEnUnCierre() {
        var mapper = new ObjectMapper();
        for (boolean cierre : java.util.List.of(false, true)) {
            for (String notas : java.util.List.of("", " ")) {
                var json = mapper.readTree(response(cierre, "5", cierre ? "3" : "null", "[\"dolor\"]"));
                ((tools.jackson.databind.node.ObjectNode) json.path("resultado")).put("observaciones", notas);
                stubProviderAndVerifier(json.toString());

                var result = client.consultar("sistema", input(), cierre);

                assertEquals(notas, result.resultado().observaciones());
                assertEquals(cierre, result.finalizado());
                assertEquals(cierre ? Integer.valueOf(3) : null, result.resultado().nivelPrioridad());
            }
        }
    }

    @Test
    void textosClinicosPendientesPuedenEstarVaciosSoloAntesDelCierre() {
        var mapper = new ObjectMapper();
        for (String campo : java.util.List.of("motivoConsulta", "inicio", "evolucion", "posibilidadEmbarazo")) {
            for (boolean cierre : java.util.List.of(false, true)) {
                for (String valor : java.util.List.of("", " ")) {
                    var json = mapper.readTree(response(cierre, "5", cierre ? "3" : "null", "[\"dolor\"]"));
                    ((tools.jackson.databind.node.ObjectNode) json.path("resultado")).put(campo, valor);
                    stubProviderAndVerifier(json.toString());
                    if (cierre) {
                        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()), campo);
                    } else {
                        var result = client.consultar("sistema", input());
                        assertFalse(result.finalizado());
                        assertEquals(valor, mapper.valueToTree(result.resultado()).path(campo).asText());
                    }
                }
            }
        }
    }

    @Test
    void textosDescriptivosSiempreDebenEstarPresentesYSerStrings() {
        var mapper = new ObjectMapper();
        for (String campo : java.util.List.of("motivoConsulta", "inicio", "evolucion", "posibilidadEmbarazo", "observaciones")) {
            for (boolean cierre : java.util.List.of(false, true)) {
                for (String tipo : java.util.List.of("ausente", "null", "numero")) {
                    var json = mapper.readTree(response(cierre, "5", cierre ? "3" : "null", "[\"dolor\"]"));
                    var resultado = (tools.jackson.databind.node.ObjectNode) json.path("resultado");
                    switch (tipo) {
                        case "ausente" -> resultado.remove(campo);
                        case "null" -> resultado.putNull(campo);
                        case "numero" -> resultado.put(campo, 12);
                    }
                    when(geminiClient.generar(anyString(), anyString(), any())).thenReturn(json.toString());
                    assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()), campo + ":" + tipo);
                }
            }
        }
    }

    @Test
    void recepcionAceptaNotasVaciasConservandoElResultadoDelProveedor() {
        var json = new ObjectMapper().readTree(response(true, "5", "3", "[\"dolor\"]"));
        ((tools.jackson.databind.node.ObjectNode) json.path("resultado")).put("observaciones", "");
        when(geminiClient.generar(anyString(), anyString(), any())).thenReturn(json.path("resultado").toString());
        var result = client.clasificarResultado("sistema", "conversacion");
        assertEquals("", result.observaciones());
        assertEquals(3, result.nivelPrioridad());
        verify(geminiClient).generar(anyString(), anyString(), any());
        verify(geminiClient, org.mockito.Mockito.never()).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void cierreOAtencionInmediataSiguenExigiendoRecomendacionNoVacia() {
        for (boolean cierre : java.util.List.of(false, true)) {
            for (String recomendacion : java.util.List.of("", " ")) {
                String raw = response(cierre, "5", cierre ? "4" : "null", "[\"dolor\"]")
                        .replace("Consulte si empeora.", recomendacion);
                if (!cierre) raw = raw.replace("\"requiereAtencionInmediata\": false", "\"requiereAtencionInmediata\": true");
                stubProviderAndVerifier(raw);
                assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
            }
        }
    }

    @Test
    void recomendacionIntermediaDebeSeguirPresenteYSerTexto() {
        for (String valor : java.util.List.of("null", "12", "true")) {
            String raw = response(false, "null", "null", "[\"dolor\"]")
                    .replace("\"Consulte si empeora.\"", valor);
            when(geminiClient.generar(anyString(), anyString(), any())).thenReturn(raw);
            assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        }
        var json = new ObjectMapper().readTree(response(false, "null", "null", "[\"dolor\"]"));
        ((tools.jackson.databind.node.ObjectNode) json.path("resultado")).remove("recomendacionSeguridad");
        when(geminiClient.generar(anyString(), anyString(), any())).thenReturn(json.toString());
        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
    }

    @Test
    void rechazaCierreSinResultadoYAcotaLosIntentos() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn("{\"finalizado\":true,\"mensaje\":\"Listo\",\"resultado\":null}");
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));

        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void rechazaJsonMalformadoSinReintentosIlimitados() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn("{esto no es json");
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));

        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void aceptaCierreValidoConPrioridad() {
        stubProviderAndVerifier(response(true, "5", "3", "[\"dolor de garganta\"]"));
        clearInvocations(geminiClient);

        TriageAiResponse result = client.consultar("sistema", input());

        assertTrue(result.finalizado());
        assertEquals(3, result.resultado().nivelPrioridad());
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> responseSchema = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> verifierSchema = ArgumentCaptor.forClass(Map.class);
        verify(geminiClient).generar(anyString(), anyString(), responseSchema.capture());
        verify(geminiClient).generar(anyString(), anyString(), verifierSchema.capture(), any());
        assertEquals(java.util.Set.of("finalizado", "resultado", "mensaje", "estadoEntrada", "evidenciaClinica"),
                ((Map<?, ?>) responseSchema.getValue().get("properties")).keySet());
        assertEquals(java.util.Set.of("valido", "motivo"),
                ((Map<?, ?>) verifierSchema.getValue().get("properties")).keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clasificadorPuedeRecibirCorreccionEspecificaPorInicioAfirmadoOmitido() {
        when(geminiClient.generar(anyString(), anyString(), any(), any(java.time.Duration.class)))
                .thenReturn(response(true, "5", "3", "[\"dolor de garganta\"]"),
                        "{\"valido\":false,\"motivo\":\"OMISION_INICIO\"}");

        ProveedorIaException error = assertThrows(ProveedorIaException.class,
                () -> client.consultar("sistema", input(), true));

        assertEquals(java.util.Optional.of(TriageIaClient.MotivoCorreccionFinal.OMISION_INICIO),
                TriageIaClient.motivoCorreccionFinal(error));
        ArgumentCaptor<Map<String, Object>> schemas = ArgumentCaptor.forClass(Map.class);
        verify(geminiClient, times(2)).generar(anyString(), anyString(), schemas.capture(), any());
        Map<?, ?> verifierProperties = (Map<?, ?>) schemas.getAllValues().get(1).get("properties");
        Map<?, ?> motive = (Map<?, ?>) verifierProperties.get("motivo");
        var alternatives = (List<Map<String, Object>>) motive.get("anyOf");
        assertTrue(alternatives.get(0).get("enum").toString().contains("OMISION_INICIO"));
    }

    @Test
    void rechazaCierreConPrioridadNula() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn(response(true, "5", "null", "[\"dolor\"]"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void rechazaNumerosFueraDeRango() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn(response(false, "11", "null", "[\"dolor\"]"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void rechazaPrioridadFueraDeRango() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn(response(true, "5", "6", "[\"dolor\"]"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void rechazaSintomasNoInformadosEnCierre() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn(response(true, "2", "2", "[\"no informado\"]"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    void rechazaRespuestaRealDeAlarmaSinMotivoUtil() {
        when(geminiClient.generar(anyString(), anyString(), any(), any()))
                .thenReturn("""
                        {
                          "finalizado": true,
                          "estadoEntrada": "RELEVANTE",
                          "evidenciaClinica": [
                            {"campo":"sintomas","valor":"dolor de pecho muy fuerte","cita":"dolor de pecho muy fuerte"},
                            {"campo":"sintomas","valor":"dificultad para respirar","cita":"dificultad para respirar"},
                            {"campo":"signosAlarma","valor":"dificultad para respirar","cita":"dificultad para respirar"}
                          ],
                          "resultado": {
                            "motivoConsulta": "no informado",
                            "sintomas": ["dolor de pecho muy fuerte", "dificultad para respirar"],
                            "inicio": "desde ayer",
                            "evolucion": "no informado",
                            "intensidadDolor": null,
                            "signosAlarma": ["dificultad para respirar"],
                            "antecedentesRelevantes": [],
                            "medicamentos": [],
                            "alergias": [],
                            "posibilidadEmbarazo": "no informado",
                            "observaciones": "Dolor de pecho muy fuerte y dificultad para respirar desde hace diez minutos.",
                            "nivelPrioridad": 5,
                            "requiereAtencionInmediata": true,
                            "recomendacionSeguridad": "Busque atencion medica urgente."
                          },
                          "mensaje": "Gracias. Registre tus sintomas y la preclasificacion."
                        }
                        """);
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input(), true));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void fallaConUnaSolaLlamadaSiElProveedorNoResponde() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class, () -> client.consultar("sistema", input()));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enviaEsquemaNativoDeGeminiConRangosNumericos() {
        when(geminiClient.generar(anyString(), anyString(), any()))
                .thenReturn(response(false, "null", "null", "[\"dolor\"]"));
        client.consultar("sistema", input());
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(geminiClient).generar(anyString(), anyString(), schema.capture());
        String value = schema.getValue().toString();
        assertTrue(value.contains("minimum=0"));
        assertTrue(value.contains("maximum=10"));
        assertTrue(value.contains("minimum=1"));
        assertTrue(value.contains("maximum=5"));
        Map<?, ?> rootProperties = (Map<?, ?>) schema.getValue().get("properties");
        Map<?, ?> resultUnion = (Map<?, ?>) rootProperties.get("resultado");
        Map<?, ?> resultSchema = (Map<?, ?>) ((java.util.List<?>) resultUnion.get("anyOf")).get(0);
        Map<?, ?> resultProperties = (Map<?, ?>) resultSchema.get("properties");
        assertEquals(0, ((Map<?, ?>) resultProperties.get("recomendacionSeguridad")).get("minLength"));
        for (String campo : java.util.List.of("motivoConsulta", "inicio", "evolucion", "posibilidadEmbarazo", "observaciones")) {
            assertEquals(0, ((Map<?, ?>) resultProperties.get(campo)).get("minLength"), campo);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void modoFinalExigeFinalizadoTrueYPrioridadNoNulaEnElEsquema() {
        stubProviderAndVerifier(response(true, "5", "3", "[\"dolor\"]"));
        client.consultar("sistema", input(), true);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(geminiClient, times(2)).generar(anyString(), anyString(), schema.capture(), any());
        Map<?, ?> properties = (Map<?, ?>) schema.getAllValues().get(0).get("properties");
        Map<?, ?> finalizado = (Map<?, ?>) properties.get("finalizado");
        Map<?, ?> resultUnion = (Map<?, ?>) properties.get("resultado");
        Map<?, ?> resultSchema = (Map<?, ?>) ((java.util.List<?>) resultUnion.get("anyOf")).get(0);
        Map<?, ?> resultProperties = (Map<?, ?>) resultSchema.get("properties");
        Map<?, ?> priority = (Map<?, ?>) resultProperties.get("nivelPrioridad");
        assertEquals("boolean", finalizado.get("type"));
        assertEquals("integer", priority.get("type"));
        assertFalse(priority.containsKey("anyOf"));
        assertEquals(1, ((Map<?, ?>) resultProperties.get("recomendacionSeguridad")).get("minLength"));
        for (String campo : java.util.List.of("motivoConsulta", "inicio", "evolucion", "posibilidadEmbarazo")) {
            assertEquals(1, ((Map<?, ?>) resultProperties.get(campo)).get("minLength"), campo);
        }
        assertEquals(0, ((Map<?, ?>) resultProperties.get("observaciones")).get("minLength"));
        assertEquals(java.util.Set.of("valido", "motivo"),
                ((Map<?, ?>) schema.getAllValues().get(1).get("properties")).keySet());
    }

    @Test
    void modoFinalRechazaRespuestaQueAunDiceNoFinalizado() {
        when(geminiClient.generar(anyString(), anyString(), any(), any()))
                .thenReturn(response(false, "null", "null", "[\"dolor\"]"));
        clearInvocations(geminiClient);

        assertThrows(ProveedorIaException.class,
                () -> client.consultar("sistema", input(), true));
        verify(geminiClient, times(1)).generar(anyString(), anyString(), any(), any());
    }

    private String response(boolean finalizado, String intensidad, String prioridad, String sintomas) {
        StringBuilder evidence = new StringBuilder("[");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(sintomas);
        boolean first = true;
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!first) evidence.append(',');
            evidence.append("{\"campo\":\"sintomas\",\"valor\":\"").append(value)
                    .append("\",\"cita\":\"").append(value).append("\"}");
            first = false;
        }
        evidence.append(']');
        return """
                {
                  "finalizado": %s,
                  "mensaje": "Respuesta",
                  "estadoEntrada": "RELEVANTE",
                  "evidenciaClinica": %s,
                  "resultado": {
                    "motivoConsulta": "dolor de garganta",
                    "sintomas": %s,
                    "inicio": "ayer",
                    "evolucion": "sin cambios",
                    "intensidadDolor": %s,
                    "signosAlarma": [],
                    "antecedentesRelevantes": [],
                    "medicamentos": [],
                    "alergias": [],
                    "posibilidadEmbarazo": "no informado",
                    "observaciones": "no informado",
                    "nivelPrioridad": %s,
                    "requiereAtencionInmediata": false,
                    "recomendacionSeguridad": "Consulte si empeora."
                  }
                }
                """.formatted(finalizado, evidence, sintomas, intensidad, prioridad);
    }

    private String input() {
        return """
                {"turnos":[{"autor":"PACIENTE","contenido":"me duele la garganta (dolor de garganta); dolor; fiebre; no informado; dolor de pecho muy fuerte; dificultad para respirar"}],"resumenNoVerificado":{"sintomas":["fiebre"]}}
                """;
    }

    private void stubProviderAndVerifier(String response) {
        org.mockito.Mockito.doReturn(response).when(geminiClient).generar(anyString(), anyString(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Map<String, Object> schema = invocation.getArgument(2);
            @SuppressWarnings("unchecked") Map<String, Object> properties =
                    schema == null ? null : (Map<String, Object>) schema.get("properties");
            return properties != null && properties.keySet().equals(java.util.Set.of("valido", "motivo"))
                    ? "{\"valido\":true}" : response;
        }).when(geminiClient).generar(anyString(), anyString(), any(), any());
    }
}
