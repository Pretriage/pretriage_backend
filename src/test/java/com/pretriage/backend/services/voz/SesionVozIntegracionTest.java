package com.pretriage.backend.services.voz;

import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.model.chat.*;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.services.AtencionHospitalService;
import com.pretriage.backend.services.ChatService;
import com.pretriage.backend.services.TriageIaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises session, close validation and ChatService together, without external providers. */
@Timeout(10)
class SesionVozIntegracionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resumenVacioNoCierraYLaEntrevistaPuedeContinuarHastaGuardarUnaClasificacion() throws Exception {
        Flujo flujo = new Flujo();
        flujo.entrada("Me duele la cabeza desde ayer.");
        flujo.finalizar(mapper.createObjectNode());

        assertFalse(flujo.chat.isFinalizado());
        assertFalse(flujo.canal.contiene("entrevista_finalizada"));
        verifyNoInteractions(flujo.ia);
        verify(flujo.repo, never()).save(any());

        flujo.completarEntrevista();
        flujo.finalizar(resumen());
        flujo.terminarGemini();

        assertTrue(flujo.chat.isFinalizado());
        assertTrue(flujo.canal.contiene("triage_finalizado"));
        assertEquals(3, flujo.chat.getMensajes().stream()
                .filter(mensaje -> mensaje.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(1, flujo.chat.getMensajes().stream()
                .filter(mensaje -> mensaje.getAutor() == AutorMensaje.BOT && mensaje.getContenido().equals("Listo"))
                .count());
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(flujo.cola, times(1)).finalizarTriageEIngresarACola(eq("auth0|voz"), any(), anyString(), any(Chat.class));
    }

    @Test
    void avisaUrgenciaAntesDelCierreYDeUnaClasificacionRestRetenida() throws Exception {
        Flujo flujo = new Flujo();
        CountDownLatch inicioRest = new CountDownLatch(1);
        CountDownLatch liberarRest = new CountDownLatch(1);
        when(flujo.ia.consultar(anyString(), anyString(), eq(true))).thenAnswer(inv -> {
            inicioRest.countDown();
            assertTrue(liberarRest.await(4, TimeUnit.SECONDS));
            return new TriageAiResponse(true, "Listo", new TriageResultDTO(
                    "Me falta el aire", List.of("falta de aire"), "no informado", "no informado", null,
                    List.of("dificultad respiratoria"), List.of(), List.of(), List.of(),
                    "no informado", "", 4, true, "Buscá atención médica urgente."));
        });

        flujo.entrada("Me falta un poco el aire.");
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
        assertTrue(flujo.conexion.cierreSolicitado.await(3, TimeUnit.SECONDS));
        flujo.sesion.alCerrar(1000, "fin");
        try {
            assertTrue(inicioRest.await(2, TimeUnit.SECONDS));
            var eventos = flujo.canal.eventos.stream().map(mapper::readTree).toList();
            var tipos = eventos.stream().map(e -> e.path("tipo").asText()).toList();
            assertEquals(1, tipos.stream().filter("aviso_urgente"::equals).count());
            assertTrue(tipos.contains("entrevista_finalizada"));
            assertTrue(tipos.indexOf("aviso_urgente") < tipos.indexOf("entrevista_finalizada"));
            assertEquals(SesionVozChat.MENSAJE_AVISO_URGENTE,
                    eventos.get(tipos.indexOf("aviso_urgente")).path("mensaje").asText());
            assertFalse(tipos.contains("triage_finalizado"));
            verifyNoInteractions(flujo.cola);
        } finally {
            liberarRest.countDown();
        }

        assertTrue(flujo.canal.cerrado.await(3, TimeUnit.SECONDS));
        flujo.sesion.alCerrar(1000, "duplicado");
        assertTrue(flujo.canal.contiene("triage_finalizado"));
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(flujo.cola, times(1)).finalizarTriageEIngresarACola(eq("auth0|voz"), any(), anyString(), any(Chat.class));
    }

    @Test
    void correccionDeDolorTardiaLlegaAGeminiAntesDeActualizarLaColaUnaSolaVez() throws Exception {
        Flujo flujo = new Flujo();
        flujo.entrada("Me duele la cabeza desde ayer.");
        flujo.completarEntrevista();
        flujo.finalizar(resumen());
        flujo.esperarRespuestaFuncion();
        assertTrue(flujo.canal.contiene("entrevista_finalizada"), "No se aceptó el preflight. eventos="
                + flujo.canal.eventos + "; mensajes=" + flujo.conexion.mensajes);
        flujo.transcripcion("outputTranscription", SesionVozChat.MENSAJE_DESPEDIDA);
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");

        assertFalse(flujo.chat.isFinalizado());
        verify(flujo.ia, never()).consultar(anyString(), anyString(), anyBoolean());
        when(flujo.ia.consultar(anyString(), contains("ahora es 8/10"), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", clasificacion(8, 4)));
        flujo.entrada("El dolor ahora es 8/10.");
        assertTrue(flujo.conexion.cierreSolicitado.await(5, TimeUnit.SECONDS), "No se cerró tras el drenaje. eventos="
                + flujo.canal.eventos + "; mensajes=" + flujo.conexion.mensajes);
        verify(flujo.ia, never()).consultar(anyString(), anyString(), anyBoolean());

        flujo.sesion.alCerrar(1000, "fin");
        assertTrue(flujo.canal.cerrado.await(3, TimeUnit.SECONDS));
        flujo.sesion.alCerrar(1000, "duplicado");

        var resultado = mapper.readTree(flujo.chat.getResultadoTriageJson());
        assertEquals(8, resultado.path("intensidadDolor").intValue());
        assertTrue(resultado.path("inicio").asText().contains("ayer"));
        assertEquals(4, resultado.path("nivelPrioridad").intValue());
        assertEquals("GEMINI", resultado.path("origenClasificacion").asText());
        assertTrue(flujo.chat.getMensajes().stream().anyMatch(m -> m.getContenido().contains("ahora es 8/10")));
        assertEquals(1, flujo.chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.BOT && m.getContenido().equals("Listo"))
                .count());
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(flujo.cola, times(1)).finalizarTriageEIngresarACola(eq("auth0|voz"), any(), anyString(), any(Chat.class));
    }

    @Test
    void falloDeGeminiDejaClasificacionVozPendienteConTranscripcionYJobDurable() throws Exception {
        Flujo flujo = new Flujo();
        when(flujo.ia.consultar(anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("sin cuota"));
        flujo.entrada("Me duele la cabeza desde ayer.");
        flujo.completarEntrevista();
        flujo.finalizar(resumen());
        flujo.terminarGemini();
        assertPendingRecovery(flujo, false, 1);
        assertNull(flujo.chat.getResultadoTriageJson());
        assertFalse(flujo.canal.contiene("triage_finalizado"));
        assertEquals(3, flujo.chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertPatientMessages(flujo, "Me duele la cabeza desde ayer.",
                "El dolor es 2/10 y se mantiene.", "No, no tengo más síntomas.");
        assertFalse(flujo.chat.getMensajes().stream()
                .anyMatch(m -> m.getAutor() == AutorMensaje.BOT && m.getContenido().equals("Listo")));
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.cola);
    }

    @Test
    void falloTecnicoEnPreflightPersisteVozLiteralYEncolaClasificacionAutomatica() throws Exception {
        Flujo flujo = new Flujo();
        when(flujo.ia.consultarAlcance(anyString(), any()))
                .thenThrow(new ProveedorIaException(new IllegalStateException("sin cuota")));
        flujo.entrada("No quiero seguir respondiendo.");
        flujo.finalizar(resumen());
        flujo.terminarGemini();

        assertPendingRecovery(flujo, true, 0);
        assertNull(flujo.chat.getResultadoTriageJson());
        assertEquals(1, flujo.chat.getMensajes().stream()
                .filter(mensaje -> mensaje.getAutor() == AutorMensaje.PACIENTE).count());
        assertTrue(flujo.chat.getMensajes().stream()
                .anyMatch(mensaje -> mensaje.getAutor() == AutorMensaje.PACIENTE
                        && mensaje.getContenido().equals("No quiero seguir respondiendo.")));
        assertPatientMessages(flujo, "No quiero seguir respondiendo.");
        assertFalse(flujo.canal.contiene("triage_finalizado"));
        verify(flujo.ia, times(1)).consultarAlcance(anyString(), any());
        verify(flujo.ia, never()).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.cola);
    }

    @Test
    void rechazoGlobalSinToolSeProcesaAutomaticamenteDesdeLaTranscripcionGuardada() throws Exception {
        Flujo flujo = new Flujo();
        flujo.entrada("Me duele la cabeza desde ayer.");
        flujo.completarEntrevista();
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}"); // único nudge
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}"); // no tool-call
        assertTrue(flujo.conexion.cierreSolicitado.await(5, TimeUnit.SECONDS));
        assertTrue(flujo.canal.contiene("entrevista_finalizada"));
        assertFalse(flujo.canal.eventos.stream().anyMatch(e -> e.contains("\"resumen\"")));

        flujo.sesion.alCerrar(1000, "fin");

        assertTrue(flujo.canal.cerrado.await(3, TimeUnit.SECONDS));
        assertTrue(flujo.chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.COMPLETADA, flujo.chat.getEstadoClasificacion());
        assertEquals(1, flujo.canal.eventos.stream()
                .filter(e -> e.contains("\"tipo\":\"triage_finalizado\"")).count());
        assertEquals(3, flujo.chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertTrue(flujo.chat.getMensajes().stream().anyMatch(m -> m.getContenido().contains("No, no tengo más síntomas.")));
        assertEquals(1, flujo.chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.BOT && m.getContenido().equals("Listo"))
                .count());
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(flujo.cola, times(1)).finalizarTriageEIngresarACola(eq("auth0|voz"), any(), anyString(), any(Chat.class));
    }

    @Test
    void falloInternoRestTrasCierreAutomaticoDejaJobPendienteSinClasificarLaCola() throws Exception {
        Flujo flujo = new Flujo();
        when(flujo.ia.consultar(anyString(), anyString(), anyBoolean()))
                .thenThrow(new ProveedorIaException(new IllegalStateException("sin cuota")));
        flujo.entrada("Me duele la cabeza desde ayer.");
        flujo.completarEntrevista();
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
        flujo.sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
        assertTrue(flujo.conexion.cierreSolicitado.await(5, TimeUnit.SECONDS));

        flujo.sesion.alCerrar(1000, "fin");

        assertTrue(flujo.canal.cerrado.await(3, TimeUnit.SECONDS));
        assertPendingRecovery(flujo, false, 1);
        assertNull(flujo.chat.getResultadoTriageJson());
        assertFalse(flujo.canal.contiene("triage_finalizado"));
        assertEquals(3, flujo.chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertPatientMessages(flujo, "Me duele la cabeza desde ayer.",
                "El dolor es 2/10 y se mantiene.", "No, no tengo más síntomas.");
        assertFalse(flujo.chat.getMensajes().stream()
                .anyMatch(m -> m.getAutor() == AutorMensaje.BOT && m.getContenido().equals("Listo")));
        verify(flujo.ia, times(1)).consultar(anyString(), anyString(), anyBoolean());
        verifyNoInteractions(flujo.cola);
    }

    @Test
    void entrevistaEmocionalSeCierraSinExigirCuantificacionFisica() throws Exception {
        Flujo flujo = new Flujo();
        when(flujo.ia.consultar(anyString(), contains("vacío en el pecho"), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias por contarlo", clasificacionEmocional()));
        var turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Siento un vacío en el pecho."),
                new TurnoVoz(AutorMensaje.BOT, "¿Desde cuándo sentís ese vacío?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Desde que me dejó."),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar sobre cómo te sentís?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No, la extraño mucho. No puedo comer."));
        flujo.entrada("Siento un vacío en el pecho.");
        flujo.transcripcion("outputTranscription", "¿Desde cuándo sentís ese vacío?");
        flujo.entrada("Desde que me dejó.");
        flujo.transcripcion("outputTranscription", "¿Hay algo más que quieras contar sobre cómo te sentís?");
        flujo.entrada("No, la extraño mucho. No puedo comer.");

        assertTrue(ValidadorCierreEntrevistaVoz.tieneDatosSuficientes(turnos));
        flujo.finalizar(resumenEmocional());
        flujo.terminarGemini();

        assertTrue(flujo.chat.isFinalizado());
        assertTrue(flujo.chat.getMensajes().stream().anyMatch(m -> m.getContenido().contains("Desde que me dejó")));
        assertTrue(flujo.chat.getMensajes().stream().anyMatch(m -> m.getContenido().contains("No puedo comer")));
        verify(flujo.ia, times(1)).consultar(anyString(), contains("vacío en el pecho"), eq(true));
        verify(flujo.cola, times(1)).finalizarTriageEIngresarACola(eq("auth0|voz"), any(), anyString(), any(Chat.class));
    }

    private void assertPendingRecovery(Flujo flujo, boolean cierreParcial, int intentos) throws Exception {
        assertTrue(flujo.chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.PENDIENTE, flujo.chat.getEstadoClasificacion());
        assertEquals(cierreParcial, flujo.chat.isCierreParcial());
        assertTrue(flujo.chat.isRecuperacionAutomaticaClasificacion());
        assertTrue(flujo.chat.isClasificacionAutomaticaVoz());
        assertEquals(intentos, flujo.chat.getIntentosClasificacionAutomaticos());
        assertEquals(EstadoRecuperacionChat.REINTENTANDO, flujo.chat.getEstadoRecuperacion());
        assertNotNull(flujo.chat.getProximoReintentoEn());
        assertNull(flujo.chat.getResultadoTriageJson());
        assertTrue(flujo.canal.contiene("estado_chat"));
        assertFalse(flujo.canal.contiene("procesamiento_finalizado"));
        String evento = flujo.canal.eventos.stream()
                .filter(json -> {
                    try {
                        return "estado_chat".equals(mapper.readTree(json).path("tipo").asText());
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Falta snapshot estado_chat: " + flujo.canal.eventos));
        assertEquals("PENDIENTE", mapper.readTree(evento).path("chat").path("estadoClasificacion").asText());
    }

    private void assertPatientMessages(Flujo flujo, String... expected) {
        List<String> persisted = flujo.chat.getMensajes().stream()
                .filter(mensaje -> mensaje.getAutor() == AutorMensaje.PACIENTE)
                .map(Mensaje::getContenido)
                .toList();
        assertTrue(persisted.containsAll(List.of(expected)), "Turnos literales perdidos: " + persisted);
    }

    private TriageResultDTO clasificacion(int dolor, int prioridad) {
        return new TriageResultDTO("dolor de cabeza", List.of("dolor de cabeza"), "desde ayer", "se mantiene",
                dolor, List.of(), List.of(), List.of(), List.of(), "no informado", "Caso de prueba", prioridad,
                false, "Consultar si empeora");
    }

    private TriageResultDTO clasificacionEmocional() {
        return new TriageResultDTO("vacío en el pecho desde que me dejó",
                List.of("vacío en el pecho", "dificultad para comer"), "desde que me dejó",
                "No puede comer y la extraña", null, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Malestar emocional por una separación", 2,
                false, "Consultar con un profesional de salud");
    }

    private ResumenEntrevistaVoz resumen() {
        return new ResumenEntrevistaVoz("dolor de cabeza", List.of("dolor de cabeza"),
                "hoy", "se mantiene", 2, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Niega signos de alarma, antecedentes, medicacion y alergias.");
    }

    private ResumenEntrevistaVoz resumenEmocional() {
        return new ResumenEntrevistaVoz("Siento un vacío en el pecho desde que me dejó",
                List.of("vacío emocional", "dificultad para comer"), "desde que me dejó",
                "No puede comer y la extraña", null, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Malestar emocional por una separación; no se informan síntomas físicos.");
    }

    private final class Flujo {
        final Chat chat = new Chat(new Paciente());
        final RepoChat repo = mock(RepoChat.class);
        final TriageIaClient ia = mock(TriageIaClient.class);
        final AtencionHospitalService cola = mock(AtencionHospitalService.class);
        final Conexion conexion = new Conexion();
        final Canal canal = new Canal();
        final SesionVozChat sesion;
        private int numeroLlamadaFinalizar;
        private String ultimaLlamadaFinalizar;

        Flujo() {
            when(ia.consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class)))
                    .thenAnswer(inv -> ia.consultar(inv.getArgument(0), inv.getArgument(1), true));
            chat.setId(7L);
            chat.agregarMensaje(new Mensaje("Cual es el motivo?", AutorMensaje.BOT, null));
            when(repo.findByIdAndPacienteUsuarioAuthId(7L, "auth0|voz")).thenReturn(Optional.of(chat));
            when(repo.findForUpdate(7L, "auth0|voz")).thenReturn(Optional.of(chat));
            when(repo.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth0|voz"))
                    .thenReturn(Optional.of(chat));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(ia.consultar(anyString(), anyString(), anyBoolean()))
                    .thenReturn(new TriageAiResponse(true, "Listo", clasificacion(2, 3)));
            when(ia.consultarAlcance(anyString(), any())).thenReturn(EstadoEntradaChat.RELEVANTE);
            when(cola.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                    .thenReturn(new TiempoEstimadoAtencionResponse());
            ChatService service = new ChatService(repo, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
                    mock(RepoEntradasCola.class), cola, ia, mapper);
            GeminiLiveCliente gemini = mock(GeminiLiveCliente.class);
            when(gemini.conectar(any())).thenReturn(CompletableFuture.completedFuture(conexion));
            sesion = new SesionVozChat("7", "auth0|voz",
                    List.of(new MensajeDTO("Cual es el motivo?", "BOT", LocalDateTime.now())), service,
                    gemini, new GeminiLiveProperties("test", "test", "wss://example.invalid", "Kore",
                    "es-US", Duration.ofSeconds(1)), mapper, canal);
            sesion.iniciar();
            sesion.alRecibir("{\"setupComplete\":{}}");
        }

        void entrada(String texto) {
            transcripcion("inputTranscription", texto);
        }

        void transcripcion(String tipo, String texto) {
            var evento = mapper.createObjectNode();
            evento.putObject("serverContent").putObject(tipo).put("text", texto);
            sesion.alRecibir(evento.toString());
        }

        void completarEntrevista() {
            transcripcion("outputTranscription", "¿Cuánto te duele del 1 al 10?");
            entrada("El dolor es 2/10 y se mantiene.");
            transcripcion("outputTranscription", "¿Hay algo más que quieras contar sobre lo que te pasa?");
            entrada("No, no tengo más síntomas.");
        }

        void finalizar(Object resumen) {
            var evento = mapper.createObjectNode();
            var call = evento.putObject("toolCall").putArray("functionCalls").addObject();
            ultimaLlamadaFinalizar = "fin-voz-" + (++numeroLlamadaFinalizar);
            call.put("id", ultimaLlamadaFinalizar).put("name", "finalizar_entrevista");
            call.set("args", mapper.valueToTree(resumen));
            sesion.alRecibir(evento.toString());
        }

        void esperarRespuestaFuncion() throws InterruptedException {
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String respuesta = null;
            while (System.nanoTime() < limite) {
                respuesta = conexion.mensajes.stream()
                        .filter(mensaje -> mensaje.contains("toolResponse")
                                && mensaje.contains("\"id\":\"" + ultimaLlamadaFinalizar + "\""))
                        .findFirst().orElse(null);
                if (respuesta != null) break;
                Thread.sleep(10);
            }
            assertNotNull(respuesta, "No llegó toolResponse. mensajes=" + conexion.mensajes
                    + "; eventos=" + canal.eventos);
            assertFalse(respuesta.contains("\"error\""), "El cierre fue rechazado: " + respuesta);
        }

        void terminarGemini() throws Exception {
            esperarRespuestaFuncion();
            transcripcion("outputTranscription", SesionVozChat.MENSAJE_DESPEDIDA);
            sesion.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
            assertTrue(conexion.cierreSolicitado.await(5, TimeUnit.SECONDS), "No se cerró tras despedida. eventos="
                    + canal.eventos + "; mensajes=" + conexion.mensajes);
            sesion.alCerrar(1000, "fin");
            assertTrue(canal.cerrado.await(3, TimeUnit.SECONDS));
        }
    }

    private static final class Conexion implements GeminiLiveConexion {
        final CountDownLatch cierreSolicitado = new CountDownLatch(1);
        final List<String> mensajes = new CopyOnWriteArrayList<>();
        public void enviar(String mensajeJson) { mensajes.add(mensajeJson); }
        public void cerrar() { cierreSolicitado.countDown(); }
    }

    private final class Canal implements CanalVozCliente {
        final List<String> eventos = new CopyOnWriteArrayList<>();
        final CountDownLatch cerrado = new CountDownLatch(1);
        public void enviarEvento(String eventoJson) { eventos.add(eventoJson); }
        public void enviarAudio(byte[] audioPcm) { }
        public void cerrar() { cerrado.countDown(); }
        boolean contiene(String tipo) {
            return eventos.stream().anyMatch(e -> tipo.equals(mapper.readTree(e).path("tipo").asText()));
        }
    }
}
