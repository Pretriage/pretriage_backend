package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EstadoEntradaChat;
import com.pretriage.backend.exceptions.ProveedorIaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HardeningIaClientTest {
    private GeminiJsonClient gemini;
    private TriageIaClient client;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiJsonClient.class);
        client = new TriageIaClient(gemini, new ObjectMapper());
    }

    @Test
    void continuacionVerificaLaAclaracionAmbiguaAntesDeExponerla() {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"¿Qué querés decir con harina de trigo?\",\"estadoEntrada\":\"AMBIGUO\"}",
                        "{\"valido\":true}");

        var result = client.consultarContinuacion("reglas",
                "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"me duele la harina de trigo\"}],"
                        + "\"objetivoDelTurno\":{\"tipo\":\"ACLARACION\",\"sintomaId\":null,\"dimension\":null}}",
                java.time.Duration.ofSeconds(2));

        assertFalse(result.finalizado());
        assertNull(result.resultado());
        assertEquals(EstadoEntradaChat.AMBIGUO, result.estadoEntrada());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini, times(2)).generar(prompt.capture(), payload.capture(), schema.capture(), any());
        assertTrue(prompt.getAllValues().get(0).contains("recetas"));
        assertTrue(prompt.getAllValues().get(0).contains("objetivoDelTurno"));
        String verifierPrompt = normalizarPrompt(prompt.getAllValues().get(1));
        assertTrue(verifierPrompt.contains("explicar con palabras propias"));
        assertTrue(verifierPrompt.contains("no ofrezcas interpretaciones ni opciones"));
        assertTrue(verifierPrompt.contains("ni preguntes por dolor, duracion, intensidad"));
        assertTrue(payload.getAllValues().get(1).contains("\"tipo\":\"ACLARACION\""));
        assertEquals(java.util.Set.of("mensaje", "estadoEntrada"),
                ((Map<?, ?>) schema.getAllValues().get(0).get("properties")).keySet());
        assertEquals(2, ((Map<?, ?>) schema.getAllValues().get(0).get("properties")).size());
    }

    @Test
    void aclaracionInicialAbiertaRetomaLaExpresionSinCategorizarla() {
        String input = """
                {"turnos":[{"autor":"PACIENTE","contenido":"Siento un vacío en el pecho."}],
                 "objetivoDelTurno":{"tipo":"ACLARACION","sintomaId":null,"dimension":null}}
                """;
        String openQuestion = "¿Podés explicar con tus palabras qué significa para vos ese vacío en el pecho?";
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"" + openQuestion + "\",\"estadoEntrada\":\"AMBIGUO\"}",
                        "{\"valido\":true}");

        var response = client.consultarContinuacion("Aclara el motivo.", input, java.time.Duration.ofSeconds(3));

        assertEquals(openQuestion, response.mensaje());
        assertEquals(EstadoEntradaChat.AMBIGUO, response.estadoEntrada());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).generar(prompts.capture(), payloads.capture(), any(), any());
        String verifier = normalizarPrompt(prompts.getAllValues().get(1));
        assertTrue(verifier.contains("retomando de forma neutral la expresion ambigua"));
        assertTrue(verifier.contains("no ofrezcas interpretaciones ni opciones"));
        assertTrue(verifier.contains("una distincion concreta solo"));
        assertTrue(payloads.getAllValues().get(1).contains("Siento un vacío en el pecho."));
        assertTrue(payloads.getAllValues().get(1).contains(openQuestion));
    }

    @Test
    void verificadorNoExigeDuracionCalendariaSiElInicioRelativoYaFueRespondido() {
        String input = """
                {"turnos":[
                  {"autor":"PACIENTE","contenido":"Me siento vacía desde que terminó la relación."},
                  {"autor":"BOT","contenido":"¿Desde cuándo te sentís así?"},
                  {"autor":"PACIENTE","contenido":"Desde que me dejó."}],
                 "objetivoDelTurno":{"tipo":"INVITACION","sintomaId":null,"dimension":null}}
                """;
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"¿Hay algo más que quieras contar?\",\"estadoEntrada\":\"RELEVANTE\"}",
                        "{\"valido\":true}");

        var result = client.consultarContinuacion("Solo haz la invitación abierta indicada.", input,
                java.time.Duration.ofSeconds(3));

        assertEquals("¿Hay algo más que quieras contar?", result.mensaje());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schemas = ArgumentCaptor.forClass(Map.class);
        verify(gemini, times(2)).generar(prompts.capture(), anyString(), schemas.capture(), any());
        String verifierPrompt = prompts.getAllValues().get(1).replaceAll("\\s+", " ");
        assertTrue(verifierPrompt.contains("referencia temporal relativa"));
        assertTrue(verifierPrompt.contains("desde que me dejó"));
        assertTrue(verifierPrompt.contains("no exijas que la traduzca a horas o días"));
        assertTrue(verifierPrompt.contains("no inventes una fecha"));
        assertTrue(verifierPrompt.contains("no lo trates como pendiente"));
        assertEquals(java.util.Set.of("mensaje", "estadoEntrada"),
                ((Map<?, ?>) schemas.getAllValues().get(0).get("properties")).keySet());
        assertEquals(java.util.Set.of("valido", "motivo"),
                ((Map<?, ?>) schemas.getAllValues().get(1).get("properties")).keySet());
    }

    @Test
    void verificadorPreguntaRechazaRecetaAunqueElModeloMarqueRelevanteYCompartePlazo() {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"La receta es...\",\"estadoEntrada\":\"RELEVANTE\"}",
                        "{\"valido\":false,\"motivo\":\"COMUNICACION\"}");

        var error = assertThrows(ProveedorIaException.class, () -> client.consultarContinuacion("reglas",
                "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"Dame una receta\"}]}",
                java.time.Duration.ofSeconds(3)));

        assertTrue(TriageIaClient.esErrorDeRespuesta(error), "debe habilitar la corrección de una respuesta inválida");
        assertEquals(java.util.Optional.of(TriageIaClient.MotivoRechazoSemantico.COMUNICACION),
                TriageIaClient.motivoRechazo(error));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.time.Duration> budget = ArgumentCaptor.forClass(java.time.Duration.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini, times(2)).generar(prompt.capture(), payload.capture(), schema.capture(), budget.capture());
        assertTrue(prompt.getAllValues().get(1).contains("código"));
        assertTrue(prompt.getAllValues().get(1).contains("pre-triage"));
        assertTrue(payload.getAllValues().get(1).contains("Dame una receta"));
        assertTrue(payload.getAllValues().get(1).contains("La receta es"));
        assertTrue(budget.getAllValues().get(1).compareTo(budget.getAllValues().get(0)) <= 0);
        assertTrue(budget.getAllValues().get(1).compareTo(java.time.Duration.ZERO) > 0);
        assertEquals(java.util.Set.of("valido", "motivo"),
                ((Map<?, ?>) schema.getAllValues().get(1).get("properties")).keySet());
    }

    @Test
    void verificadorPreguntaRechazaAnuncioDeRegistroDuranteContinuacion() {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"Gracias. Ya registré tus respuestas para el equipo médico.\",\"estadoEntrada\":\"RELEVANTE\"}",
                        "{\"valido\":false}");

        var error = assertThrows(ProveedorIaException.class, () -> client.consultarContinuacion("reglas",
                input("Me siento triste."), java.time.Duration.ofSeconds(3)));

        assertTrue(TriageIaClient.esErrorDeRespuesta(error));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).generar(prompt.capture(), anyString(), any(), any());
        assertTrue(prompt.getAllValues().get(1).contains("Una continuación no puede anunciar el registro"));
    }

    @Test
    void verificadorPreguntaRecibeObjetivoCanonicoParaRechazarOtraDimensionPendiente() {
        String input = """
                {"turnos":[
                  {"autor":"PACIENTE","contenido":"Tengo cefalea y fiebre. Desde ayer."},
                  {"autor":"BOT","contenido":"¿Pudiste medir la temperatura?"}],
                 "objetivoDelTurno":{"tipo":"DATO","sintomaId":"dolor:cabeza","dimension":"INTENSIDAD"}}
                """;
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"mensaje\":\"¿Pudiste medir la temperatura?\",\"estadoEntrada\":\"RELEVANTE\"}",
                        "{\"valido\":false,\"motivo\":\"INFERENCIA\"}");

        var error = assertThrows(ProveedorIaException.class, () -> client.consultarContinuacion(
                "Pregunta solamente por el objetivo del turno.", input, java.time.Duration.ofSeconds(3)));

        assertEquals(java.util.Optional.of(TriageIaClient.MotivoRechazoSemantico.INFERENCIA),
                TriageIaClient.motivoRechazo(error));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).generar(prompts.capture(), payloads.capture(), any(), any());
        assertTrue(prompts.getAllValues().get(0).contains("objetivoDelTurno"));
        assertTrue(prompts.getAllValues().get(1).contains("Rechaza proponer otro dato aunque también esté pendiente"));
        assertTrue(payloads.getAllValues().get(1).contains("\"dimension\":\"INTENSIDAD\""));
        assertTrue(payloads.getAllValues().get(1).contains("¿Pudiste medir la temperatura?"));
    }

    @Test
    void rechazoSemanticoSeRegistraUnaVezConClasificacionCorrectaYSinTextoDelPaciente() {
        String patientText = "Me duele la garganta. CONFIDENCIAL: no puedo comer desde que me dejó";
        stubChatResponse(finalResponse("Me duele la garganta."), "{\"valido\":false,\"motivo\":\"INFERENCIA\"}");
        Logger logger = (Logger) LoggerFactory.getLogger(TriageIaClient.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            assertThrows(ProveedorIaException.class,
                    () -> client.consultar("reglas", input(patientText), true));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        assertEquals(1, appender.list.size());
        String logged = appender.list.get(0).getFormattedMessage();
        assertTrue(logged.contains("category=invalid_response field=semantic_verification reason=rejected"));
        assertTrue(logged.contains("motivo=INFERENCIA"));
        assertFalse(logged.contains("malformed_or_unreadable"));
        assertFalse(logged.contains("CONFIDENCIAL"));
        assertFalse(logged.contains("no puedo comer"));
    }

    @Test
    void aceptaMalestarEmocionalSinExigirSintomasFisicosNiConvertirMetaforaEnAlarma() {
        String input = "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"La extraño. Siento un vacío en el pecho desde que me dejó. No puedo comer.\"}]}";
        String proposal = """
                {"finalizado":true,"mensaje":"Gracias. Ya registré tus respuestas.","estadoEntrada":"RELEVANTE",
                 "resultado":{"motivoConsulta":"Vacío en el pecho y dificultad para comer","sintomas":["vacío en el pecho","no poder comer"],"inicio":"desde que me dejó",
                 "evolucion":"no informado","intensidadDolor":null,"signosAlarma":[],"antecedentesRelevantes":[],
                 "medicamentos":[],"alergias":[],"posibilidadEmbarazo":"no informado","observaciones":"Relacionado a un evento emocional.",
                 "nivelPrioridad":2,"requiereAtencionInmediata":false,"recomendacionSeguridad":"Consulte si empeora."},
                 "evidenciaClinica":[{"campo":"sintomas","valor":"vacío en el pecho","cita":"Siento un vacío en el pecho"},
                 {"campo":"sintomas","valor":"no poder comer","cita":"No puedo comer"}]}
                """;
        stubChatResponse(proposal, "{\"valido\":true}");

        var response = client.consultar("reglas", input, true);

        assertTrue(response.finalizado());
        assertEquals(List.of(), response.resultado().signosAlarma());
        assertFalse(response.resultado().requiereAtencionInmediata());
        ArgumentCaptor<String> verificationPrompt = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).generar(verificationPrompt.capture(), anyString(), any(), any());
        assertTrue(verificationPrompt.getAllValues().get(1).contains("no exijas que el paciente mencione uno"));
        assertTrue(verificationPrompt.getAllValues().get(1).contains("sin inferir dolor toracico, alarma ni diagnostico"));
    }

    @Test
    void aclaracionEmocionalExplicitaNoSeConvierteEnDolorToracicoNiAlarma() {
        String input = """
                {"turnos":[
                  {"autor":"PACIENTE","contenido":"Siento un vacío en el pecho."},
                  {"autor":"BOT","contenido":"¿Podés explicar con tus palabras qué significa ese vacío en el pecho?"},
                  {"autor":"PACIENTE","contenido":"Quiero decir que estoy triste; no me duele el pecho."}]}
                """;
        String proposal = """
                {"finalizado":true,"mensaje":"Gracias. Ya registré tus respuestas para el equipo médico.",
                 "estadoEntrada":"RELEVANTE",
                 "resultado":{"motivoConsulta":"Malestar emocional expresado como un vacío en el pecho",
                 "sintomas":["malestar emocional"],"inicio":"no informado","evolucion":"no informado",
                 "intensidadDolor":null,"signosAlarma":[],"antecedentesRelevantes":[],"medicamentos":[],
                 "alergias":[],"posibilidadEmbarazo":"no informado","observaciones":"",
                 "nivelPrioridad":2,"requiereAtencionInmediata":false,"recomendacionSeguridad":"Consulte si empeora."},
                 "evidenciaClinica":[{"campo":"sintomas","valor":"malestar emocional","cita":"estoy triste"}]}
                """;
        stubChatResponse(proposal, "{\"valido\":true}");

        var result = client.consultar("reglas", input, true);

        assertTrue(result.finalizado());
        assertEquals(List.of("malestar emocional"), result.resultado().sintomas());
        assertNull(result.resultado().intensidadDolor());
        assertEquals(List.of(), result.resultado().signosAlarma());
        assertFalse(result.resultado().requiereAtencionInmediata());
        assertEquals(2, result.resultado().nivelPrioridad());
    }

    @Test
    void consultarAlcanceDevuelveSoloEstadoDelCasoCompletoYUsaElPlazoRecibido() {
        var budget = java.time.Duration.ofSeconds(4);
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{\"estadoEntrada\":\"RELEVANTE\"}");

        var state = client.consultarAlcance(
                "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"Me duele la cabeza.\"}]}", budget);

        assertEquals(EstadoEntradaChat.RELEVANTE, state);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini).generar(prompt.capture(), anyString(), schema.capture(), org.mockito.ArgumentMatchers.eq(budget));
        assertTrue(prompt.getValue().contains("entrevista completa"));
        assertEquals(java.util.Set.of("estadoEntrada"), ((Map<?, ?>) schema.getValue().get("properties")).keySet());
    }

    @Test
    void fueraDeContextoNoPuedeDevolverUnaClasificacionNiEvidencia() {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("""
                        {"finalizado":false,"mensaje":"No se pudo completar la entrevista de salud.",
                         "estadoEntrada":"FUERA_DE_CONTEXTO","resultado":null,"evidenciaClinica":[]}
                        """);

        var result = client.consultar("reglas", input("¿Me pasás una receta?"), true);

        assertFalse(result.finalizado());
        assertNull(result.resultado());
        assertEquals(EstadoEntradaChat.FUERA_DE_CONTEXTO, result.estadoEntrada());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(1)).generar(prompt.capture(), anyString(), any(), any());
        assertTrue(prompt.getValue().contains("estadoEntrada=RELEVANTE, finalizado=true"));
        assertTrue(prompt.getValue().contains("finalizado=false, resultado=null y evidenciaClinica=[]"));
        assertTrue(prompt.getValue().contains("requiereAtencionInmediata=true y nivelPrioridad=4 o 5"));
        assertFalse(prompt.getValue().contains("objetivoDelTurno"));
    }

    @Test
    void rechazaMezclarElEstadoDeAlcanceConLaRamaDeCierreOpuesta() {
        String sinHechos = """
                {"estadoEntrada":"AMBIGUO","finalizado":false,"mensaje":"Entrevista incompleta.",
                 "resultado":null,"evidenciaClinica":[]}
                """;
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(sinHechos.replace("\"finalizado\":false", "\"finalizado\":true"),
                        sinHechos.replace("\"AMBIGUO\"", "\"RELEVANTE\""));

        assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", input("No sé qué decir."), true));
        assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", input("Tengo fiebre."), true));
    }

    @Test
    void clasificacionRequiereCitasEnTurnosPacienteYNoAceptaBotNiResumen() {
        for (String citation : List.of("¿Desde cuándo te duele?", "dolor de garganta", "Me duele la garganta.")) {
            String input = """
                    {"turnos":[
                      {"autor":"BOT","contenido":"dolor de garganta"},
                      {"autor":"PACIENTE","contenido":"Me duele la garganta."}],
                     "resumenNoVerificado":{"sintomas":["dolor de garganta"]}}
                    """;
            stubChatResponse(finalResponse(citation), "{\"valido\":true}");
            if (citation.equals("Me duele la garganta.")) {
                var result = client.consultar("reglas", input, true);
                assertTrue(result.finalizado());
                assertEquals(EstadoEntradaChat.RELEVANTE, result.estadoEntrada());
            } else {
                assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", input, true));
            }
        }
        verify(gemini, times(4)).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void noAceptaAfirmacionesSinCitaYNoQuitaNegacionesParaValidar() {
        String noEvidence = finalResponse("Me duele la garganta.").replace(
                "\"evidenciaClinica\":[{\"campo\":\"sintomas\",\"valor\":\"dolor de garganta\",\"cita\":\"Me duele la garganta.\"}]",
                "\"evidenciaClinica\":[]");
        stubChatResponse(noEvidence, "{\"valido\":true}");
        assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", input("Me duele la garganta."), true));

        String negated = """
                {"turnos":[{"autor":"PACIENTE","contenido":"No."}]}
                """;
        stubChatResponse(finalResponse("me duele la garganta"), "{\"valido\":true}");
        assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", negated, true));
    }

    @Test
    void verificadorSemanticoBloqueaLaClasificacionAunqueLaCitaLiteralCoincida() {
        stubChatResponse(finalResponse("Me duele la harina de trigo."), "{\"valido\":false}");

        var error = assertThrows(ProveedorIaException.class,
                () -> client.consultar("reglas", input("Me duele la harina de trigo."), true));

        assertTrue(TriageIaClient.esErrorDeRespuesta(error));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini, times(2)).generar(prompt.capture(), payload.capture(), schema.capture(), any());
        assertTrue(prompt.getAllValues().get(1).contains("negación"));
        assertTrue(prompt.getAllValues().get(1).contains("negación funcional"));
        assertTrue(prompt.getAllValues().get(1).contains("escala referida a \"vacío\""));
        assertTrue(prompt.getAllValues().get(1).contains("no la atribuyas a un estado emocional"));
        assertTrue(prompt.getAllValues().get(1).contains("me duele la harina de trigo"));
        assertTrue(payload.getAllValues().get(1).contains("entradaOriginal"));
        assertTrue(payload.getAllValues().get(1).contains("clasificacionPropuesta"));
        assertTrue(payload.getAllValues().get(1).contains("turnos"));
        assertEquals(java.util.Set.of("valido", "motivo"), ((Map<?, ?>) schema.getAllValues().get(1).get("properties")).keySet());
        Map<?, ?> verifierProperties = (Map<?, ?>) schema.getAllValues().get(1).get("properties");
        List<?> reasonOptions = (List<?>) ((Map<?, ?>) verifierProperties.get("motivo")).get("anyOf");
        assertEquals("null", ((Map<?, ?>) reasonOptions.get(1)).get("type"));
        verify(gemini, times(2)).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void razonSemanticaEsEnumAcotadoYLaFormaLegacySigueSiendoAceptada() {
        stubChatResponse(finalResponse("Me duele la garganta."), "{\"valido\":false,\"motivo\":\"NEGACION\"}");
        var rejected = assertThrows(ProveedorIaException.class,
                () -> client.consultar("reglas", input("No me duele la garganta."), true));
        assertEquals(java.util.Optional.of(TriageIaClient.MotivoRechazoSemantico.NEGACION),
                TriageIaClient.motivoRechazo(rejected));

        stubChatResponse(finalResponse("Me duele la garganta."), "{\"valido\":false}");
        var legacy = assertThrows(ProveedorIaException.class,
                () -> client.consultar("reglas", input("Me duele la garganta."), true));
        assertTrue(TriageIaClient.motivoRechazo(legacy).isEmpty());
    }

    @Test
    void motivoNuloEsCompatibleTantoEnVeredictoValidoComoEnRechazoGenerico() {
        stubChatResponse(finalResponse("Me duele la garganta."), "{\"valido\":true,\"motivo\":null}");
        var accepted = client.consultar("reglas", input("Me duele la garganta."), true);
        assertTrue(accepted.finalizado());

        stubChatResponse(finalResponse("Me duele la garganta."), "{\"valido\":false,\"motivo\":null}");
        var genericRejection = assertThrows(ProveedorIaException.class,
                () -> client.consultar("reglas", input("Me duele la garganta."), true));
        assertTrue(TriageIaClient.motivoRechazo(genericRejection).isEmpty());
    }

    @Test
    void verificacionRechazaRazonDesconocidaYVeredictoVerdaderoConRazon() {
        for (String verdict : List.of(
                "{\"valido\":false,\"motivo\":\"dato-clinico-secreto\"}",
                "{\"valido\":true,\"motivo\":\"INFERENCIA\"}")) {
            stubChatResponse(finalResponse("Me duele la garganta."), verdict);
            var error = assertThrows(ProveedorIaException.class,
                    () -> client.consultar("reglas", input("Me duele la garganta."), true));
            assertTrue(TriageIaClient.motivoRechazo(error).isEmpty());
        }
    }

    @Test
    void verificadorFinalTambienRechazaDatosDeAlergiaQueSoloAparecenEnResumen() throws Exception {
        var mapper = new ObjectMapper();
        var proposal = (tools.jackson.databind.node.ObjectNode) mapper.readTree(finalResponse("Me duele la garganta."));
        ((tools.jackson.databind.node.ObjectNode) proposal.path("resultado"))
                .putArray("alergias").add("penicilina");
        String input = """
                {"turnos":[{"autor":"PACIENTE","contenido":"Me duele la garganta."}],
                 "resumenNoVerificado":{"alergias":["penicilina"]}}
                """;
        stubChatResponse(proposal.toString(), "{\"valido\":false}");

        assertThrows(ProveedorIaException.class, () -> client.consultar("reglas", input, true));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(2)).generar(prompt.capture(), payload.capture(), any(), any());
        String verifierPrompt = prompt.getAllValues().get(1);
        assertTrue(verifierPrompt.contains("alergias"));
        assertTrue(verifierPrompt.contains("medicamentos"));
        assertTrue(verifierPrompt.contains("antecedentesRelevantes"));
        assertTrue(payload.getAllValues().get(1).contains("penicilina"));
    }

    @Test
    void verificadorRechazaCitaLiteralQueEsSubstringDeUnaNegacion() {
        stubChatResponse(finalResponse("me duele la garganta"), "{\"valido\":false}");

        var error = assertThrows(ProveedorIaException.class,
                () -> client.consultar("reglas", input("No me duele la garganta."), true));

        assertTrue(TriageIaClient.esErrorDeRespuesta(error));
        verify(gemini, times(2)).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void evidenciaComparaMayusculasYEspaciosSinEliminarContenidoClinico() {
        stubChatResponse(finalResponse("ME DUELE la garganta"), "{\"valido\":true}");

        var result = client.consultar("reglas",
                input("Me duele   la garganta."), true);

        assertTrue(result.finalizado());
        verify(gemini, times(2)).generar(anyString(), anyString(), any(), any());
    }

    @Test
    void verificadorNoSeInvocaParaGuardNoRelevante() {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn("""
                        {"finalizado":false,"mensaje":"¿Qué querés decir?","estadoEntrada":"AMBIGUO",
                         "resultado":null,"evidenciaClinica":[]}
                        """);

        var result = client.consultar("reglas", input("Me duele la harina de trigo."), true);

        assertEquals(EstadoEntradaChat.AMBIGUO, result.estadoEntrada());
        verify(gemini, times(1)).generar(anyString(), anyString(), any(), any());
    }

    private String input(String patientText) {
        return "{\"turnos\":[{\"autor\":\"PACIENTE\",\"contenido\":\"" + patientText + "\"}]}";
    }

    private String normalizarPrompt(String prompt) {
        return prompt.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void stubChatResponse(String response, String verification) {
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(response, verification);
    }

    private String finalResponse(String citation) {
        return """
                {"finalizado":true,"mensaje":"Gracias. Ya registré tus respuestas.","estadoEntrada":"RELEVANTE",
                 "resultado":{"motivoConsulta":"dolor de garganta","sintomas":["dolor de garganta"],"inicio":"hoy",
                 "evolucion":"sin cambios","intensidadDolor":null,"signosAlarma":[],"antecedentesRelevantes":[],
                 "medicamentos":[],"alergias":[],"posibilidadEmbarazo":"no informado","observaciones":"",
                 "nivelPrioridad":2,"requiereAtencionInmediata":false,"recomendacionSeguridad":"Consulte si empeora."},
                 "evidenciaClinica":[{"campo":"sintomas","valor":"dolor de garganta","cita":"%s"}]}
                """.formatted(citation);
    }
}
