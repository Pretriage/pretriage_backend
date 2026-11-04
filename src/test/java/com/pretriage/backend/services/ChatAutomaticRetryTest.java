package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real chat/provider boundaries, fake HTTP only: retries must not duplicate domain side effects. */
class ChatAutomaticRetryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    private final RepoChat chats = mock(RepoChat.class);
    private final AtencionHospitalService queue = mock(AtencionHospitalService.class);
    private final Chat chat = new Chat(new Paciente());

    private ChatService service() {
        chat.setId(7L);
        when(chats.findByIdAndPacienteUsuarioAuthId(7L, "patient")).thenReturn(Optional.of(chat));
        when(chats.findForUpdate(7L, "patient")).thenReturn(Optional.of(chat));
        var properties = new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(3), 8192,
                2, Duration.ofSeconds(1), Duration.ZERO);
        var gemini = new GeminiJsonClient(properties, mapper, http);
        return new ChatService(chats, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
                mock(RepoEntradasCola.class), queue, new TriageIaClient(gemini, mapper), mapper);
    }

    @Test
    void error503RecuperadoEntregaUnaPreguntaYGuardaUnSoloTurno() throws Exception {
        var unavailable = response(503, "");
        var success = response(200, envelope(mapper.writeValueAsString(
                Map.of("mensaje", "¿Podés contarme qué sentís?", "estadoEntrada", "RELEVANTE"))));
        var verified = response(200, envelope("{\"valido\":true}"));
        when(http.<String>send(any(), any())).thenReturn(unavailable, success, verified);

        var result = service().enviarMensaje("7", "patient", "Me siento raro");

        assertFalse(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals("GEMINI", result.origenRespuesta());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT).count());
        verify(http, times(3)).send(any(), any());
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void recetaMarcadaRelevanteSeCorrigeSinGuardarLaRespuestaFueraDeAlcance() throws Exception {
        String receta = mapper.writeValueAsString(Map.of("mensaje", "Mezclá harina, azúcar y chocolate.",
                "estadoEntrada", "RELEVANTE"));
        String pregunta = mapper.writeValueAsString(Map.of("mensaje", "¿Podés contarme qué sentís?",
                "estadoEntrada", "RELEVANTE"));
        var unsafe = response(200, envelope(receta));
        var rejected = response(200, envelope("{\"valido\":false}"));
        var safe = response(200, envelope(pregunta));
        var verified = response(200, envelope("{\"valido\":true}"));
        when(http.<String>send(any(), any())).thenReturn(unsafe, rejected, safe, verified);

        var response = service().enviarMensaje("7", "patient", "Me siento raro");

        assertEquals("¿Podés contarme qué sentís?", response.respuesta().contenido());
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(2, chat.getMensajes().size());
        assertTrue(chat.getMensajes().stream().noneMatch(m -> m.getContenido().contains("harina")));
        verify(http, times(4)).send(any(), any());
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void rechazoSemanticoDePreguntaGuiaUnaCorreccionConUnSoloTurnoYSinEfectosDeCola() throws Exception {
        var firstQuestion = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "Ya registré tus respuestas para el equipo médico.", "estadoEntrada", "RELEVANTE")));
        var correctedQuestion = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "¿Desde cuándo te duele la cabeza?", "estadoEntrada", "RELEVANTE")));
        var responses = List.of(response(200, firstQuestion),
                response(200, envelope("{\"valido\":false,\"motivo\":\"COMUNICACION\"}")),
                response(200, correctedQuestion), response(200, envelope("{\"valido\":true}")));
        var requests = responderConCaptura(responses);

        var result = service().enviarMensaje("7", "patient", "Tengo dolor de cabeza");

        assertEquals("¿Desde cuándo te duele la cabeza?", result.respuesta().contenido());
        assertEquals(2, chat.getMensajes().size());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT).count());
        assertEquals(4, requests.size(), "una generación/verificación y una corrección/verificación");
        String correctionPrompt = requestSystemInstruction(requests.get(2));
        assertTrue(correctionPrompt.contains("No menciones registro, despedida, clasificación, triage ni resultados."));
        assertFalse(correctionPrompt.contains("invalid_question_response"));
        assertFalse(requestBody(requests.get(2)).contains("Ya registré tus respuestas"));
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void rechazoComunicacionEnInvitacionEmocionalSeCorrigeSinPedirOtroDato() throws Exception {
        prepararAclaracionEmocionalConTurnoInicialExcluido();
        String announcement = "Ya registré tus respuestas para el equipo médico.";
        String empatheticInvitation = "Lamento que estés pasando por eso. ¿Hay algo más que quieras contar sobre cómo te sentís?";
        var requests = responderConCaptura(List.of(
                response(200, envelope(mapper.writeValueAsString(Map.of(
                        "mensaje", announcement, "estadoEntrada", "RELEVANTE")))),
                response(200, envelope("{\"valido\":false,\"motivo\":\"COMUNICACION\"}")),
                response(200, envelope(mapper.writeValueAsString(Map.of(
                        "mensaje", empatheticInvitation, "estadoEntrada", "RELEVANTE")))),
                response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "No sé, lo siento desde que me dejó");

        assertEquals(empatheticInvitation, result.respuesta().contenido());
        assertFalse(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(5, chat.getMensajes().size(), "la respuesta del paciente y una sola invitación deben agregarse una vez");
        assertEquals(2, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && "No sé, lo siento desde que me dejó".equals(m.getContenido())).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT
                && empatheticInvitation.equals(m.getContenido())).count());
        assertTrue(chat.getMensajes().stream().noneMatch(m -> announcement.equals(m.getContenido())));
        assertTrue(chat.getMensajes().get(1).getExcluidoDelTriage(),
                "el turno inicial excluido debe conservar su flag local");
        assertEquals(4, requests.size(), "una generación/verificación y una corrección/verificación");

        String correctionPrompt = normalizarPrompt(requestSystemInstruction(requests.get(2)));
        assertTrue(correctionPrompt.contains("el objetivo sigue siendo una invitación abierta"));
        assertTrue(correctionPrompt.contains("pregunta en una sola invitación si quiere agregar algo sobre lo que ya contó"));
        assertTrue(correctionPrompt.contains("no pidas un dato ni sugieras síntomas nuevos"));
        assertTrue(correctionPrompt.contains("no cierres ni anuncies el registro"));
        assertTrue(correctionPrompt.contains("reconocimiento neutral puede preceder inmediatamente a esa única invitación"));

        var providerInput = mapper.readTree(requestContentText(requests.get(0)));
        assertEquals("INVITACION", providerInput.path("objetivoDelTurno").path("tipo").asText());
        assertTrue(requestContentText(requests.get(0)).contains("No sé, lo siento desde que me dejó"));
        assertFalse(requestContentText(requests.get(0)).contains("Me pasa algo raro y no sé cómo explicarlo"),
                "el turno excluido no debe volver como evidencia del proveedor");
        assertFalse(requestBody(requests.get(2)).contains(announcement),
                "la respuesta rechazada no se reenvía como instrucción en el reintento");
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void dobleRechazoComunicacionEnInvitacionEmocionalRevierteTurnoYRespetaDosIntentos() throws Exception {
        prepararAclaracionEmocionalConTurnoInicialExcluido();
        String announcement = "Ya registré tus respuestas para el equipo médico.";
        String rejectedCorrection = "Siento mucho que estés pasando por eso; ya quedó registrado para el equipo médico.";
        var requests = responderConCaptura(List.of(
                response(200, envelope(mapper.writeValueAsString(Map.of(
                        "mensaje", announcement, "estadoEntrada", "RELEVANTE")))),
                response(200, envelope("{\"valido\":false,\"motivo\":\"COMUNICACION\"}")),
                response(200, envelope(mapper.writeValueAsString(Map.of(
                        "mensaje", rejectedCorrection, "estadoEntrada", "RELEVANTE")))),
                response(200, envelope("{\"valido\":false,\"motivo\":\"COMUNICACION\"}"))));

        assertThrows(com.pretriage.backend.exceptions.ProveedorIaException.class,
                () -> service().enviarMensaje("7", "patient", "No sé, lo siento desde que me dejó"));

        assertFalse(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(3, chat.getMensajes().size(), "solo queda la historia previa; el nuevo mensaje se revierte");
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && m.getContenido().equals("Me pasa algo raro y no sé cómo explicarlo")).count());
        assertTrue(chat.getMensajes().get(1).getExcluidoDelTriage());
        assertTrue(chat.getMensajes().stream().noneMatch(m -> m.getContenido().equals(announcement)
                || m.getContenido().equals(rejectedCorrection)
                || m.getContenido().equals("No sé, lo siento desde que me dejó")));
        assertEquals(4, requests.size(), "no debe haber una tercera generación ni una segunda corrección");
        String correctionPrompt = normalizarPrompt(requestSystemInstruction(requests.get(2)));
        assertTrue(correctionPrompt.contains("el objetivo sigue siendo una invitación abierta"));
        assertTrue(correctionPrompt.contains("no pidas un dato ni sugieras síntomas nuevos"));
        assertTrue(correctionPrompt.contains("no cierres ni anuncies el registro"));
        verify(chats, never()).save(any());
        verifyNoInteractions(queue);
    }

    private void prepararAclaracionEmocionalConTurnoInicialExcluido() {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Hola. Contame qué te pasa.", AutorMensaje.BOT, null));
        var initialPatientTurn = new com.pretriage.backend.model.chat.Mensaje(
                "Me pasa algo raro y no sé cómo explicarlo", AutorMensaje.PACIENTE, chat.getPaciente());
        initialPatientTurn.setExcluidoDelTriage(true);
        chat.agregarMensaje(initialPatientTurn);
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Podés contarme con tus palabras qué querés decir con eso?", AutorMensaje.BOT, null));
    }

    @Test
    void aclaracionInicialCorrigeLaPreguntaDeOpcionesAUnaInvitacionAbierta() throws Exception {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Cuál es el principal motivo de consulta?", AutorMensaje.BOT, null));
        String binaryQuestion = "¿Ese vacío en el pecho es una sensación física o emocional?";
        String openQuestion = "¿Podés explicar con tus palabras qué significa para vos ese vacío en el pecho?";
        var rejectedByObjective = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", binaryQuestion, "estadoEntrada", "AMBIGUO")));
        var corrected = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", openQuestion, "estadoEntrada", "AMBIGUO")));
        var requests = responderConCaptura(List.of(response(200, rejectedByObjective),
                response(200, envelope("{\"valido\":true}")), response(200, corrected),
                response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "Siento un vacío en el pecho");

        assertEquals(openQuestion, result.respuesta().contenido());
        assertTrue(chat.getMensajes().stream().noneMatch(m -> binaryQuestion.equals(m.getContenido())));
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && "Siento un vacío en el pecho".equals(m.getContenido())).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT
                && openQuestion.equals(m.getContenido())).count());
        assertEquals(4, requests.size(), "una redacción/verificación y una única corrección/verificación");

        String correctionPrompt = normalizarPrompt(requestSystemInstruction(requests.get(2)));
        assertTrue(correctionPrompt.contains("invita a la persona a explicar con sus propias palabras"));
        assertTrue(correctionPrompt.contains("no la categorices ni le ofrezcas opciones físicas o emocionales"));
        assertTrue(correctionPrompt.contains("tampoco preguntes por dolor, duración, intensidad, causas o síntomas nuevos todavía"));
        assertFalse(correctionPrompt.contains("aclaracion_fuera_de_objetivo"),
                "El rechazo local se convierte en feedback legible, no en un código para Gemini.");
        assertFalse(requestBody(requests.get(2)).contains(binaryQuestion),
                "La respuesta rechazada no se adjunta como instrucción en el reintento.");
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void rechazoSemanticoDeCategoriasEnAclaracionGuiaLaMismaAclaracionAbierta() throws Exception {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Cuál es el principal motivo de consulta?", AutorMensaje.BOT, null));
        String binaryQuestion = "¿Ese vacío en el pecho es una sensación física o emocional?";
        String openQuestion = "¿Podés contarme con tus palabras qué significa para vos ese vacío en el pecho?";
        var rejected = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", binaryQuestion, "estadoEntrada", "AMBIGUO")));
        var corrected = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", openQuestion, "estadoEntrada", "AMBIGUO")));
        var requests = responderConCaptura(List.of(response(200, rejected),
                response(200, envelope("{\"valido\":false,\"motivo\":\"INFERENCIA\"}")),
                response(200, corrected), response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "Siento un vacío en el pecho");

        assertEquals(openQuestion, result.respuesta().contenido());
        assertEquals(4, requests.size(), "una redacción/verificación y una única corrección/verificación");
        String correctionPrompt = normalizarPrompt(requestSystemInstruction(requests.get(2)));
        assertTrue(correctionPrompt.contains("invita a la persona a explicar con sus propias palabras"));
        assertTrue(correctionPrompt.contains("no la categorices ni le ofrezcas opciones físicas o emocionales"));
        assertTrue(correctionPrompt.contains("sin repetir la pregunta anterior"));
        assertFalse(correctionPrompt.contains("question_semantic_verification"));
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void segundoRechazoSemanticoDePreguntaNoDuplicaTurnoNiEncola() throws Exception {
        var firstQuestion = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "Ya registré tus respuestas para el equipo médico.", "estadoEntrada", "RELEVANTE")));
        var correctedQuestion = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "Tu dolor se debe a una enfermedad grave.", "estadoEntrada", "RELEVANTE")));
        var responses = List.of(response(200, firstQuestion),
                response(200, envelope("{\"valido\":false,\"motivo\":\"COMUNICACION\"}")),
                response(200, correctedQuestion), response(200, envelope("{\"valido\":false,\"motivo\":\"INFERENCIA\"}")));
        var requests = responderConCaptura(responses);

        assertThrows(com.pretriage.backend.exceptions.ProveedorIaException.class,
                () -> service().enviarMensaje("7", "patient", "Tengo dolor de cabeza"));

        assertFalse(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().isEmpty(), "El turno fallido se revierte una sola vez y no guarda respuesta del bot.");
        assertEquals(4, requests.size(), "No debe haber una segunda corrección después del rechazo corregido.");
        assertTrue(requestSystemInstruction(requests.get(2))
                .contains("No menciones registro, despedida, clasificación, triage ni resultados."));
        verify(chats, never()).save(any());
        verifyNoInteractions(queue);
    }

    @Test
    void preguntaAmbiguaDeTemperaturaSeCorrigeAlObjetivoDeIntensidadTrasDosInicios() throws Exception {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Cuál es el motivo principal?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Tengo cefalea y fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Desde cuándo empezó la cefalea?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Desde ayer", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Desde cuándo empezó la fiebre?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Desde ayer", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Tenés algún otro síntoma además de los que mencionaste?", AutorMensaje.BOT, null));

        var wrongAmbiguous = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "¿Pudiste medir la temperatura?", "estadoEntrada", "AMBIGUO")));
        var correctedIntensity = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "¿Qué intensidad tiene el dolor de cabeza?", "estadoEntrada", "RELEVANTE")));
        var requests = responderConCaptura(List.of(response(200, wrongAmbiguous),
                response(200, envelope("{\"valido\":true}")), response(200, correctedIntensity),
                response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "No");

        assertEquals("¿Qué intensidad tiene el dolor de cabeza?", result.respuesta().contenido());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && "No".equals(m.getContenido())).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT
                && m.getContenido().equals("¿Qué intensidad tiene el dolor de cabeza?")).count());
        assertTrue(chat.getMensajes().stream().noneMatch(m -> m.getContenido().equals("¿Pudiste medir la temperatura?")),
                "AMBIGUO no debe evitar la validación local del objetivo ni persistir la pregunta desviada.");
        assertEquals(4, requests.size());

        String correctionPrompt = requestSystemInstruction(requests.get(2));
        assertTrue(correctionPrompt.contains("intensidad"));
        String firstVerificationInput = requestContentText(requests.get(1));
        String correctedVerificationInput = requestContentText(requests.get(3));
        assertTrue(firstVerificationInput.contains("\"objetivoDelTurno\""));
        assertTrue(firstVerificationInput.contains("\"dimension\":\"INTENSIDAD\""));
        assertTrue(correctedVerificationInput.contains("\"dimension\":\"INTENSIDAD\""));
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void aclaracionPuedeRepetirLaUltimaPreguntaPendiente() throws Exception {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Cuál es el motivo?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        String pendingQuestion = "¿Qué temperatura te tomaste?";
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                pendingQuestion, AutorMensaje.BOT, null));
        var response = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", pendingQuestion, "estadoEntrada", "RELEVANTE")));
        var requests = responderConCaptura(List.of(response(200, response),
                response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "38 kg celsius");

        assertEquals(pendingQuestion, result.respuesta().contenido());
        assertTrue(chat.getMensajes().stream().filter(m -> "38 kg celsius".equals(m.getContenido()))
                .findFirst().orElseThrow().getExcluidoDelTriage());
        assertEquals(2, requests.size());
        assertTrue(requestSystemInstruction(requests.get(0)).contains("temperatura"));
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    @Test
    void soloLaUltimaPreguntaPuedeRepetirseDuranteUnaAclaracionPendiente() throws Exception {
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "¿Cuál es el motivo?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "Tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        String olderQuestion = "¿Tenés alergias?";
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                olderQuestion, AutorMensaje.BOT, null));
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                "No", AutorMensaje.PACIENTE, chat.getPaciente()));
        String pendingQuestion = "¿Qué temperatura te tomaste?";
        chat.agregarMensaje(new com.pretriage.backend.model.chat.Mensaje(
                pendingQuestion, AutorMensaje.BOT, null));
        var repeated = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", olderQuestion, "estadoEntrada", "RELEVANTE")));
        var corrected = envelope(mapper.writeValueAsString(Map.of(
                "mensaje", "¿Mediste la temperatura?", "estadoEntrada", "RELEVANTE")));
        var requests = responderConCaptura(List.of(response(200, repeated),
                response(200, envelope("{\"valido\":true}")), response(200, corrected),
                response(200, envelope("{\"valido\":true}"))));

        var result = service().enviarMensaje("7", "patient", "38 cm");

        assertEquals("¿Mediste la temperatura?", result.respuesta().contenido());
        assertEquals(4, requests.size());
        assertTrue(requestSystemInstruction(requests.get(2)).contains("repeated_question"));
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && "38 cm".equals(m.getContenido())).count());
        verify(chats, times(1)).save(chat);
        verifyNoInteractions(queue);
    }

    private List<HttpRequest> responderConCaptura(List<HttpResponse<String>> responses) throws Exception {
        var requests = new ArrayList<HttpRequest>();
        when(http.<String>send(any(), any())).thenAnswer(invocation -> {
            int index = requests.size();
            requests.add(invocation.getArgument(0));
            return responses.get(index);
        });
        return requests;
    }

    private String requestSystemInstruction(HttpRequest request) throws Exception {
        return mapper.readTree(requestBody(request)).path("systemInstruction").path("parts").get(0)
                .path("text").stringValue();
    }

    private String normalizarPrompt(String prompt) {
        return prompt.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String requestBody(HttpRequest request) throws Exception {
        var captured = new ByteArrayOutputStream();
        var completed = new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                captured.write(bytes, 0, bytes.length);
            }
            @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
            @Override public void onComplete() { completed.complete(null); }
        });
        completed.get(1, TimeUnit.SECONDS);
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String requestContentText(HttpRequest request) throws Exception {
        return mapper.readTree(requestBody(request)).path("contents").get(0)
                .path("parts").get(0).path("text").stringValue();
    }

    @Test
    void timeoutRecuperadoClasificaUnaSolaVezSinRepetirMensajeNiIngresoEnCola() throws Exception {
        var result = new TriageAiResponse(true, "Busca atención urgente.", new TriageResultDTO(
                "dolor de pecho", List.of("dolor de pecho"), "no informado", "no informado", null,
                List.of("dolor de pecho"), List.of(), List.of(), List.of(), "no informado", "",
                5, true, "Busca atención urgente."));
        var validatedResult = mapper.valueToTree(result);
        ((tools.jackson.databind.node.ObjectNode) validatedResult).set("evidenciaClinica", mapper.valueToTree(List.of(
                Map.of("campo", "sintomas", "valor", "dolor de pecho", "cita", "Tengo dolor de pecho"),
                Map.of("campo", "signosAlarma", "valor", "dolor de pecho", "cita", "Tengo dolor de pecho"))));
        when(http.<String>send(any(), any())).thenThrow(new HttpTimeoutException("transient"))
                .thenReturn(response(200, envelope(mapper.writeValueAsString(validatedResult))),
                        response(200, envelope("{\"valido\":true}")));

        service().enviarMensaje("7", "patient", "Tengo dolor de pecho");

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertNotNull(chat.getResultadoTriageJson());
        assertTrue(chat.getResultadoTriageJson().contains("\"nivelPrioridad\":5"));
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT).count());
        verify(http, times(3)).send(any(), any());
        verify(chats, times(1)).save(chat);
        verify(queue, times(1)).finalizarTriageEIngresarACola(eq("patient"), any(), anyString(), same(chat));
    }

    private String envelope(String text) {
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
                "content", Map.of("parts", List.of(Map.of("text", text)))))));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }
}
