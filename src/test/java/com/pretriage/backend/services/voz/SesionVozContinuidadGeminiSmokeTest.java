package com.pretriage.backend.services.voz;

import com.pretriage.backend.controllers.dtos.MensajeDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.*;
import com.pretriage.backend.services.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic Live events + real REST classifier; no Spring context, database or live microphone. */
@EnabledIfSystemProperty(named = "pretriage.gemini.hardening.live", matches = "true")
class SesionVozContinuidadGeminiSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "voz sin herramienta: {0}")
    @ValueSource(strings = {"DATOS_RESUELTOS", "ALARMA", "SOLICITUD_PACIENTE"})
    void cierraSinOtraRespuestaYClasificaUnaSolaVez(String motivoEsperado) throws Exception {
        Chat chat = new Chat(new Paciente());
        chat.setId(92L);
        chat.agregarMensaje(new Mensaje("Hola. Contame qué te pasa.", AutorMensaje.BOT, null));
        RepoChat chats = mock(RepoChat.class);
        when(chats.findByIdAndPacienteUsuarioAuthId(92L, "auth0|synthetic-voice"))
                .thenReturn(Optional.of(chat));
        when(chats.findForUpdate(92L, "auth0|synthetic-voice")).thenReturn(Optional.of(chat));
        when(chats.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth0|synthetic-voice"))
                .thenReturn(Optional.of(chat));
        when(chats.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var queue = mock(AtencionHospitalService.class);
        when(queue.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());
        var calls = new AtomicInteger();
        var real = generador();
        var delegated = mock(GeminiJsonClient.class);
        when(delegated.generar(anyString(), anyString(), any(), any(Duration.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return real.generar(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
        });
        var service = new ChatService(chats, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
                mock(RepoEntradasCola.class), queue, new TriageIaClient(delegated, mapper), mapper);
        var connection = mock(GeminiLiveConexion.class);
        var gateway = mock(GeminiLiveCliente.class);
        when(gateway.conectar(any())).thenReturn(CompletableFuture.completedFuture(connection));
        var channel = new CanalPrueba();
        var clock = new RelojPrueba();
        var session = new SesionVozChat("92", "auth0|synthetic-voice",
                List.of(new MensajeDTO("Hola. Contame qué te pasa.", "BOT", LocalDateTime.now())),
                service, gateway, new GeminiLiveProperties("unused", "models/gemini-3.8-live",
                "wss://synthetic.invalid", "Kore", "es-US", Duration.ofSeconds(1)), mapper, channel,
                clock, Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(8));
        try {
            session.iniciar();
            entrada(session, "Tengo tos desde ayer.");
            salida(session, "¿Cuánto te afecta la tos para hacer tus actividades?");
            if (motivoEsperado.equals("SOLICITUD_PACIENTE")) {
                entrada(session, "Terminá el chat, por favor.");
            } else {
                entrada(session, "Poco, puedo hacer mis actividades.");
                salida(session, "¿Querés agregar algo más?");
                entrada(session, motivoEsperado.equals("ALARMA")
                        ? "Ahora noto que me cuesta bastante respirar."
                        : "Gracias, no tengo nada más que agregar.");
            }
            session.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
            clock.avanzar(8_000);
            clock.avanzar(0);
            assertEquals(1, channel.contar("entrevista_finalizada"),
                    "no se debe necesitar otro mensaje ni una llamada de herramienta de Live");
            session.alCerrar(1000, "normal");
            assertTrue(channel.cerrado.await(55, TimeUnit.SECONDS), "el procesamiento debe terminar dentro de su límite");
            assertEquals(0, channel.contar("error"));
            assertEquals(1, channel.contar("triage_finalizado"));
            assertEquals(1, channel.contar("fin"));
            assertTrue(channel.eventos.stream().anyMatch(e -> mapper.readTree(e).path("tipo")
                    .asText().equals("procesando_respuesta")));
            assertTrue(chat.isFinalizado());
            assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
            var saved = mapper.readTree(chat.getResultadoTriageJson());
            assertEquals(motivoEsperado, saved.path("seguimientoEntrevista").path("motivoCierre").asText());
            if (motivoEsperado.equals("ALARMA")) {
                assertTrue(saved.path("requiereAtencionInmediata").asBoolean());
                assertTrue(saved.path("nivelPrioridad").asInt() >= 4);
            }
            assertEquals(motivoEsperado.equals("SOLICITUD_PACIENTE") ? 2 : 3,
                    chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
            verify(queue, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), same(chat));
            assertTrue(calls.get() >= 2 && calls.get() <= 4, "generación/verificación con una sola corrección acotada");
        } finally {
            session.cerrar();
            clock.shutdownNow();
        }
    }

    private GeminiJsonClient generador() {
        String key = System.getenv("GEMINI_API_KEY");
        assertNotNull(key, "GEMINI_API_KEY debe estar en el entorno");
        assertFalse(key.isBlank());
        return new GeminiJsonClient(new GeminiChatProperties(key, System.getenv("GEMINI_CHAT_MODEL"), null,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 8192,
                2, Duration.ofSeconds(15), Duration.ofMillis(500)), mapper);
    }

    private void entrada(SesionVozChat session, String text) { transcribir(session, "inputTranscription", text); }
    private void salida(SesionVozChat session, String text) {
        transcribir(session, "outputTranscription", text);
        session.alRecibir("{\"serverContent\":{\"turnComplete\":true}}");
    }
    private void transcribir(SesionVozChat session, String field, String text) {
        var message = mapper.createObjectNode();
        message.putObject("serverContent").putObject(field).put("text", text);
        session.alRecibir(message.toString());
    }

    private final class CanalPrueba implements CanalVozCliente {
        final List<String> eventos = new CopyOnWriteArrayList<>();
        final CountDownLatch cerrado = new CountDownLatch(1);
        public void enviarEvento(String json) { eventos.add(json); }
        public void enviarAudio(byte[] pcm) { }
        public void cerrar() { cerrado.countDown(); }
        long contar(String tipo) {
            return eventos.stream().filter(e -> mapper.readTree(e).path("tipo").asText().equals(tipo)).count();
        }
    }

    /** Advances only scheduled tasks; no network sleeps or real voice timing. */
    private static final class RelojPrueba extends ScheduledThreadPoolExecutor {
        private long ahora;
        private final List<Tarea> pendientes = new ArrayList<>();
        RelojPrueba() { super(1); }
        public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            var task = new Tarea(action, ahora + unit.toMillis(delay));
            pendientes.add(task);
            return task;
        }
        void avanzar(long milliseconds) {
            ahora += milliseconds;
            pendientes.stream().filter(t -> !t.isDone() && t.vencimiento <= ahora)
                    .sorted((a, b) -> Long.compare(a.vencimiento, b.vencimiento)).toList().forEach(Tarea::run);
        }
        private final class Tarea extends FutureTask<Void> implements ScheduledFuture<Void> {
            final long vencimiento;
            Tarea(Runnable action, long time) { super(action, null); vencimiento = time; }
            public long getDelay(TimeUnit unit) { return unit.convert(vencimiento - ahora, TimeUnit.MILLISECONDS); }
            public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS)); }
        }
    }
}
