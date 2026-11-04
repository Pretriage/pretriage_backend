package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Conversation regressions: quantify the reported complaint without a symptom checklist. */
class ChatCuantificacionFlowTest {
    private final RepoChat chats = mock(RepoChat.class);
    private final TriageIaClient provider = mock(TriageIaClient.class);
    private final AtencionHospitalService queue = mock(AtencionHospitalService.class);
    private final Chat chat = new Chat(new Paciente());
    private final ChatService service = new ChatService(chats, mock(RepoPacientes.class),
            mock(RepoConsultasMedicas.class), mock(RepoEntradasCola.class), queue, provider, new ObjectMapper());

    private void prepare() {
        chat.setId(91L);
        chat.agregarMensaje(new Mensaje("¿Cuál es el principal motivo de tu consulta?", AutorMensaje.BOT, null));
        when(chats.findByIdAndPacienteUsuarioAuthId(91L, "synthetic")).thenReturn(Optional.of(chat));
        when(provider.consultar(anyString(), anyString(), eq(true))).thenReturn(classification());
    }

    @Test
    void negarOtrosSintomasNoEvitaCuantificarLosYaInformados() {
        prepare();
        chat.agregarMensaje(new Mensaje("Me duele la cabeza y tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar?", AutorMensaje.BOT, null));
        when(provider.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(question("¿Desde cuándo empezó el dolor de cabeza?"),
                        question("¿Desde cuándo empezó la fiebre?"),
                        question("Del 0 al 10, ¿cuánto te duele la cabeza?"),
                        question("¿Qué temperatura marcó el termómetro?"));

        send("No tengo otros síntomas");
        assertFalse(chat.isFinalizado(), "Negar otros síntomas no aporta duración ni intensidad");
        send("El dolor de cabeza empezó ayer");
        assertFalse(chat.isFinalizado(), "La fiebre sigue sin inicio informado");
        send("La fiebre empezó ayer");
        assertFalse(chat.isFinalizado(), "Sigue pendiente la intensidad del dolor");
        send("8 de 10");
        assertFalse(chat.isFinalizado(), "La escala de dolor no mide la fiebre");
        send("No me medí la temperatura");

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        verify(provider, times(4)).consultarContinuacion(anyString(), anyString(), any());
        verify(provider, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(queue, times(1)).finalizarTriageEIngresarACola(eq("synthetic"), any(), anyString(), same(chat));
        assertEquals(1, chat.getMensajes().stream().filter(message -> message.getContenido()
                .equals("¿Hay algo más que quieras contar?")).count(), "No repetir la invitación");
    }

    @Test
    void rechazoExplicitoDeContinuarCierraSinForzarCuantificacion() {
        prepare();
        chat.agregarMensaje(new Mensaje("Me duele la cabeza y tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));

        send("No quiero seguir respondiendo");

        assertTrue(chat.isFinalizado());
        verify(provider, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(provider, times(1)).consultar(anyString(), anyString(), eq(true));
        assertEquals(2, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
    }

    @Test
    void inicioDeLaCefaleaNoSustituyeElInicioDeLaFiebre() {
        prepare();
        chat.agregarMensaje(new Mensaje(
                "Me duele la cabeza desde ayer, 5 de 10. También tengo fiebre de 39 grados.",
                AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(provider.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(question("¿Desde cuándo empezó la fiebre?"));

        send("No tengo otros síntomas");

        assertFalse(chat.isFinalizado(), "El inicio de la fiebre sigue desconocido");
        verify(provider, never()).consultar(anyString(), anyString(), eq(true));
        verifyNoInteractions(queue);
        send("La fiebre empezó hoy");
        assertTrue(chat.isFinalizado());
        verify(provider, times(1)).consultarContinuacion(anyString(), anyString(), any());
        verify(queue, times(1)).finalizarTriageEIngresarACola(eq("synthetic"), any(), anyString(), same(chat));
    }

    @Test
    void masDeOchoRespuestasNoCierranMientrasQuedanDatosDeSintomasPendientes() {
        prepare();
        for (String symptom : List.of("Tengo fiebre", "Tengo tos", "Tengo náuseas", "Tengo vómitos",
                "Tengo mareos", "Tengo diarrea", "Tengo sangrado", "Tengo debilidad", "Tengo sarpullido")) {
            chat.agregarMensaje(new Mensaje(symptom, AutorMensaje.PACIENTE, chat.getPaciente()));
        }
        when(provider.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(question("¿Desde cuándo empezó la fiebre?"));

        send("Tengo picazón");

        assertFalse(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        verify(provider, never()).consultar(anyString(), anyString(), eq(true));
        verify(provider, times(1)).consultarContinuacion(anyString(), anyString(), any());
        verifyNoInteractions(queue);
    }

    @Test
    void unaEntrevistaSinHechosQueNoAvanzaCierraSinClasificacionNiCola() {
        prepare();
        for (int i = 0; i < 3; i++) {
            chat.agregarMensaje(new Mensaje("No sé cómo explicarlo", AutorMensaje.PACIENTE, chat.getPaciente()));
        }
        when(provider.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(false, "No entendí bien la consulta.", null,
                        com.pretriage.backend.controllers.dtos.EstadoEntradaChat.AMBIGUO));

        var response = service.enviarMensaje("91", "synthetic", "No sé qué decir");

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals("CIERRE_SIN_DATOS", response.origenRespuesta());
        verify(provider, times(1)).consultar(anyString(), anyString(), eq(true));
        verifyNoInteractions(queue);
    }

    @Test
    void respuestaInvalidaAUnDatoSeExcluyeDelHistorialEnviadoParaContinuar() {
        prepare();
        when(provider.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(question("¿Qué temperatura te tomaste?"));
        chat.agregarMensaje(new Mensaje("Tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Qué temperatura te tomaste?", AutorMensaje.BOT, null));

        send("38 kg celsius");

        var rejectedTurn = chat.getMensajes().stream()
                .filter(m -> "38 kg celsius".equals(m.getContenido())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(rejectedTurn.getExcluidoDelTriage()));
        org.mockito.ArgumentCaptor<String> conversation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(provider, times(1)).consultarContinuacion(anyString(), conversation.capture(), any());
        assertFalse(conversation.getValue().contains("38 kg celsius"));
        assertFalse(chat.isFinalizado());
        verifyNoInteractions(queue);
    }

    @Test
    void respuestaDeTemperaturaConUnidadInvalidaNoDescartaUnaAlarmaLiteral() {
        prepare();
        chat.agregarMensaje(new Mensaje("Tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Qué temperatura te tomaste?", AutorMensaje.BOT, null));
        when(provider.consultar(anyString(), anyString(), eq(true))).thenReturn(classificationWithAlarm());

        send("38 kg celsius y me cuesta respirar");

        var patientTurn = chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.PACIENTE)
                .filter(m -> m.getContenido().contains("me cuesta respirar"))
                .findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(patientTurn.getExcluidoDelTriage()));
        assertTrue(chat.isFinalizado());
        verify(provider).consultar(anyString(), anyString(), eq(true));
        verify(provider, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(queue).finalizarTriageEIngresarACola(eq("synthetic"), any(), anyString(), same(chat));
    }

    private TriageAiResponse classificationWithAlarm() {
        return new TriageAiResponse(true, "Buscá atención urgente.",
                new TriageResultDTO("fiebre y dificultad respiratoria", List.of("fiebre", "dificultad respiratoria"),
                        "no informado", "no informado", null, List.of("dificultad respiratoria"),
                        List.of(), List.of(), List.of(), "no informado", "", 4, true,
                        "Buscá atención urgente ahora."));
    }

    private void send(String content) { service.enviarMensaje("91", "synthetic", content); }
    private TriageAiResponse question(String message) { return new TriageAiResponse(false, message, null); }
    private TriageAiResponse classification() {
        return new TriageAiResponse(true, "Gracias. Ya registré tus respuestas para el equipo médico.",
                new TriageResultDTO("dolor de cabeza y fiebre", List.of("dolor de cabeza", "fiebre"),
                        "no informado", "no informado", null, List.of(), List.of(), List.of(), List.of(),
                        "no informado", "", 3, false, "Consultá al equipo médico para la evaluación presencial."));
    }
}
