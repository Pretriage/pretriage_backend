package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.ChatTurnResponse;
import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoPacientes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatQuestionFormatTest {
    private static final String PACIENTE = "synthetic";
    private static final String MOTIVO = "Tengo cefalea";
    private static final String NUEVO_MENSAJE = "La cefalea empezó ayer";

    @Test
    void aceptaPreguntasConFormatosDePuntuacionNaturales() {
        for (String pregunta : List.of(
                "Contame la intensidad de la cefalea del uno al diez.",
                "¿Cuánto te duele la cabeza del 1 al 10??",
                "¿Cuánto te duele la cabeza del 1 al 10？",
                "¿Cuál es la intensidad del dolor de cabeza entre 1 y 10?")) {
            Flujo flujo = new Flujo();
            flujo.prepararRespuesta(question(pregunta));

            ChatTurnResponse response = flujo.enviar(NUEVO_MENSAJE);

            assertEquals(pregunta, response.respuesta().contenido());
            assertEquals("GEMINI", response.origenRespuesta());
            assertFalse(flujo.chat.isFinalizado());
            assertEquals(List.of(MOTIVO, NUEVO_MENSAJE), flujo.mensajesDe(AutorMensaje.PACIENTE));
            assertEquals(List.of("Hola. ¿Cuál es el motivo principal de tu consulta?", pregunta),
                    flujo.mensajesDe(AutorMensaje.BOT));
            verify(flujo.provider, times(1)).consultarContinuacion(anyString(), anyString(), any());
            verify(flujo.provider, never()).consultar(anyString(), anyString(), eq(true));
            verifyNoInteractions(flujo.queue);
        }
    }

    @Test
    void corrigeUnaRespuestaVaciaConUnaSolaConsultaRemotaYGuardaElTurnoUnaVez() {
        Flujo flujo = new Flujo();
        flujo.prepararRespuesta(new TriageAiResponse(false, "   ", null), question("¿Qué intensidad tiene la cefalea del 1 al 10?"));

        ChatTurnResponse response = flujo.enviar(NUEVO_MENSAJE);

        assertEquals("¿Qué intensidad tiene la cefalea del 1 al 10?", response.respuesta().contenido());
        assertEquals(List.of(MOTIVO, NUEVO_MENSAJE), flujo.mensajesDe(AutorMensaje.PACIENTE));
        assertEquals(List.of("Hola. ¿Cuál es el motivo principal de tu consulta?",
                "¿Qué intensidad tiene la cefalea del 1 al 10?"), flujo.mensajesDe(AutorMensaje.BOT));
        verify(flujo.provider, times(2)).consultarContinuacion(anyString(), anyString(), any());
        verify(flujo.provider, never()).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.queue);
    }

    @Test
    void corrigeRespuestaConResultadoOFinalizacionInesperados() {
        for (TriageAiResponse[] respuestas : List.<TriageAiResponse[]>of(
                new TriageAiResponse[]{new TriageAiResponse(false, "¿Desde cuándo?", classification()),
                        question("¿Cuánto te duele la cabeza del 1 al 10?")},
                new TriageAiResponse[]{new TriageAiResponse(true, "¿Desde cuándo?", null),
                        question("¿Cuánto te duele la cabeza del 1 al 10?")},
                new TriageAiResponse[]{null, question("¿Cuánto te duele la cabeza del 1 al 10?")})) {
            Flujo flujo = new Flujo();
            flujo.prepararRespuesta(respuestas);

            ChatTurnResponse response = flujo.enviar(NUEVO_MENSAJE);

            assertEquals("¿Cuánto te duele la cabeza del 1 al 10?", response.respuesta().contenido());
            assertFalse(flujo.chat.isFinalizado());
            assertEquals(List.of(MOTIVO, NUEVO_MENSAJE), flujo.mensajesDe(AutorMensaje.PACIENTE));
            verify(flujo.provider, times(2)).consultarContinuacion(anyString(), anyString(), any());
            verify(flujo.provider, never()).consultar(anyString(), anyString(), anyBoolean());
            verifyNoInteractions(flujo.queue);
        }
    }

    @Test
    void siLaCorreccionSigueSiendoInvalidaFallaSinFallbackYRevierteElMensajeDelPaciente() {
        Flujo flujo = new Flujo();
        flujo.prepararRespuesta(new TriageAiResponse(false, " ", null),
                new TriageAiResponse(false, "\t", null));

        assertThrows(ProveedorIaException.class, () -> flujo.enviar(NUEVO_MENSAJE));

        assertEquals(List.of(MOTIVO), flujo.mensajesDe(AutorMensaje.PACIENTE));
        assertEquals(List.of("Hola. ¿Cuál es el motivo principal de tu consulta?"),
                flujo.mensajesDe(AutorMensaje.BOT));
        assertFalse(flujo.chat.isFinalizado());
        verify(flujo.provider, times(2)).consultarContinuacion(anyString(), anyString(), any());
        verify(flujo.provider, never()).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.queue);
    }

    @Test
    void corrigePreguntaSobreDatoYaResueltoParaSeguirConElObjetivoPendiente() {
        Flujo flujo = new Flujo();
        String anterior = "¿Desde cuándo empezó la cefalea?";
        flujo.chat.agregarMensaje(new Mensaje(anterior, AutorMensaje.BOT, null));
        flujo.prepararRespuesta(question("¿Desde cuando te duele la cabeza?"),
                question("¿Qué intensidad tiene la cefalea del 1 al 10?"));

        ChatTurnResponse response = flujo.enviar(NUEVO_MENSAJE);

        assertEquals("¿Qué intensidad tiene la cefalea del 1 al 10?", response.respuesta().contenido());
        assertEquals(List.of("Hola. ¿Cuál es el motivo principal de tu consulta?", anterior,
                "¿Qué intensidad tiene la cefalea del 1 al 10?"), flujo.mensajesDe(AutorMensaje.BOT));
        assertEquals(List.of(MOTIVO, NUEVO_MENSAJE), flujo.mensajesDe(AutorMensaje.PACIENTE));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(flujo.provider, times(2)).consultarContinuacion(prompts.capture(), anyString(), timeout.capture());
        assertTrue(prompts.getAllValues().get(1).contains("incluí explícitamente la escala del 1 al 10"));
        assertTrue(timeout.getAllValues().stream().allMatch(value -> !value.isNegative()
                && !value.isZero() && value.compareTo(Duration.ofSeconds(45)) <= 0));
        verify(flujo.provider, never()).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.queue);
    }

    @Test
    void corrigePreguntasDeDolorSinEscalaValidaOQuePreguntanPorActividades() {
        for (String propuesta : List.of("¿Cuánto te duele la cabeza?",
                "¿Cuánto te duele la cabeza del 0 al 10?",
                "¿El dolor de cabeza te afecta para hacer tus actividades diarias?")) {
            Flujo flujo = new Flujo();
            String corregida = "¿Cuánto te duele la cabeza del 1 al 10?";
            flujo.prepararRespuesta(question(propuesta), question(corregida));

            assertEquals(corregida, flujo.enviar(NUEVO_MENSAJE).respuesta().contenido());
            assertFalse(flujo.mensajesDe(AutorMensaje.BOT).contains(propuesta));
            verify(flujo.provider, times(2)).consultarContinuacion(anyString(), anyString(), any());
        }
    }

    @Test
    void corrigeSiCambiaElSintomaAunqueElOtroTambienTengaElInicioPendiente() {
        Flujo flujo = new Flujo();
        flujo.prepararRespuesta(question("¿Desde cuándo empezó la tos?"),
                question("¿Desde cuándo te duele la cabeza?"));

        ChatTurnResponse response = flujo.enviar("También tengo tos.");

        assertEquals("¿Desde cuándo te duele la cabeza?", response.respuesta().contenido());
        verify(flujo.provider).consultarContinuacion(contains("SINTOMA_NO_COINCIDE"),
                contains("\"dimension\":\"INICIO\""), any());
        verifyNoInteractions(flujo.queue);
    }

    @Test
    void devuelveErrorSiSiguePreguntandoPorElDatoYaResuelto() {
        Flujo flujo = new Flujo();
        String anterior = "¿Desde cuándo empezó la cefalea?";
        flujo.chat.agregarMensaje(new Mensaje(anterior, AutorMensaje.BOT, null));
        flujo.prepararRespuesta(question("¿Desde cuándo empezó la cefalea?"),
                question("¿Desde cuándo empezó la cefalea?"));

        assertThrows(ProveedorIaException.class, () -> flujo.enviar(NUEVO_MENSAJE));

        assertEquals(List.of(MOTIVO), flujo.mensajesDe(AutorMensaje.PACIENTE));
        assertEquals(List.of("Hola. ¿Cuál es el motivo principal de tu consulta?", anterior),
                flujo.mensajesDe(AutorMensaje.BOT));
        verify(flujo.provider, times(2)).consultarContinuacion(anyString(), anyString(), any());
        verify(flujo.provider, never()).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.queue);
    }

    private static TriageAiResponse question(String message) {
        return new TriageAiResponse(false, message, null);
    }

    private static TriageResultDTO classification() {
        return new TriageResultDTO("cefalea y fiebre", List.of("cefalea", "fiebre"),
                "no informado", "no informado", null, List.of(), List.of(), List.of(), List.of(),
                "no informado", "", 3, false, "Consulta al equipo medico.");
    }

    private static final class Flujo {
        private final RepoChat chats = mock(RepoChat.class);
        private final TriageIaClient provider = mock(TriageIaClient.class);
        private final AtencionHospitalService queue = mock(AtencionHospitalService.class);
        private final Chat chat = new Chat(new Paciente());
        private final ChatService service = new ChatService(chats, mock(RepoPacientes.class),
                mock(RepoConsultasMedicas.class), mock(RepoEntradasCola.class), queue, provider, new ObjectMapper());

        private Flujo() {
            chat.setId(91L);
            chat.agregarMensaje(new Mensaje("Hola. ¿Cuál es el motivo principal de tu consulta?", AutorMensaje.BOT, null));
            chat.agregarMensaje(new Mensaje(MOTIVO, AutorMensaje.PACIENTE, chat.getPaciente()));
            when(chats.findByIdAndPacienteUsuarioAuthId(91L, PACIENTE)).thenReturn(Optional.of(chat));
        }

        private void prepararRespuesta(TriageAiResponse... respuestas) {
            when(provider.consultarContinuacion(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(respuestas[0], java.util.Arrays.copyOfRange(respuestas, 1, respuestas.length));
        }

        private ChatTurnResponse enviar(String mensaje) {
            return service.enviarMensaje("91", PACIENTE, mensaje);
        }

        private List<String> mensajesDe(AutorMensaje autor) {
            return chat.getMensajes().stream().filter(mensaje -> mensaje.getAutor() == autor)
                    .map(Mensaje::getContenido).toList();
        }
    }
}
