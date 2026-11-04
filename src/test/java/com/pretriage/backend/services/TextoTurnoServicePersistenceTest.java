package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.repositories.RepoChat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PostgreSQL-backed checks for durable ownership of accepted text turns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TextoTurnoServicePersistenceTest {
    @Autowired TextoTurnoService textoTurnoService;
    @Autowired ChatService chatService;
    @Autowired JdbcTemplate db;
    @Autowired RepoChat repoChat;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean TriageIaClient proveedor;
    @MockitoBean AtencionHospitalService cola;
    private String pacienteAuth;
    private String chatId;

    @BeforeEach
    void prepararChat() {
        pacienteAuth = "test-text-turn|" + UUID.randomUUID();
        db.update("INSERT INTO usuario_auth (id, nombre, apellido, numero_documento, tipo_documento, correo_electronico) VALUES (?, 'Paciente', 'Prueba', ?, 'DNI', ?)",
                pacienteAuth, UUID.randomUUID().toString(), UUID.randomUUID() + "@example.invalid");
        db.update("INSERT INTO paciente (auth_id) VALUES (?)", pacienteAuth);
        chatId = chatService.iniciarChat(pacienteAuth).id().toString();
    }

    @AfterEach
    void limpiarPaciente() {
        db.update("DELETE FROM mensaje WHERE chat_id IN (SELECT c.id FROM chat c JOIN paciente p ON c.paciente_id=p.id WHERE p.auth_id=?)", pacienteAuth);
        db.update("DELETE FROM chat WHERE paciente_id IN (SELECT id FROM paciente WHERE auth_id=?)", pacienteAuth);
        db.update("DELETE FROM paciente WHERE auth_id=?", pacienteAuth);
        db.update("DELETE FROM usuario_auth WHERE id=?", pacienteAuth);
    }

    @Test
    void pendienteEsVisibleYLaMismaClaveNoDuplicaElTurno() throws Exception {
        CountDownLatch proveedorIniciado = new CountDownLatch(1);
        CountDownLatch liberarProveedor = new CountDownLatch(1);
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            proveedorIniciado.countDown();
            assertTrue(liberarProveedor.await(10, TimeUnit.SECONDS));
            return new TriageAiResponse(false, "¿Desde cuándo empezó?", null);
        });

        String solicitudId = UUID.randomUUID().toString();
        var aceptado = textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudId);
        assertEquals(EstadoTurnoTexto.PENDIENTE, aceptado.estadoTurnoTexto());
        assertTrue(proveedorIniciado.await(10, TimeUnit.SECONDS));

        var visible = chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(EstadoTurnoTexto.PENDIENTE, visible.estadoTurnoTexto());
        var repetido = textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudId);
        assertEquals(solicitudId, repetido.solicitudTurnoTextoId());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(ConflictoDeEstadoException.class,
                () -> textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Mensaje distinto", UUID.randomUUID().toString())));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        liberarProveedor.countDown();
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        assertEquals(1, llamadas.get());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void entregaAtrasadaDeUnaClaveAnteriorNoDuplicaTurnoDespuesDelSiguiente() throws Exception {
        when(proveedor.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Desde cuándo empezó?", null));
        String solicitudAnterior = UUID.randomUUID().toString();
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudAnterior);
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        String solicitudNueva = UUID.randomUUID().toString();
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Sigue igual", solicitudNueva);
        esperarEstado(EstadoTurnoTexto.COMPLETADA);
        var resultadoRepetido = textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudAnterior);

        assertEquals(solicitudNueva, resultadoRepetido.solicitudTurnoTextoId());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertThrows(ConflictoDeEstadoException.class,
                () -> textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Contenido distinto", solicitudAnterior));
    }

    @Test
    void recuperaSoloReclamosVencidosYConservaElTurnoEnProceso() throws Exception {
        when(proveedor.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Desde cuándo empezó?", null));
        String solicitudId = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chat chat = repoChat.findById(Long.valueOf(chatId)).orElseThrow();
            Mensaje mensaje = new Mensaje("Tengo tos", AutorMensaje.PACIENTE, chat.getPaciente());
            mensaje.setSolicitudId(solicitudId);
            chat.agregarMensaje(mensaje);
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.PENDIENTE);
            chat.setSolicitudTurnoTextoId(solicitudId);
            chat.setIntentosTurnoTexto(1);
            chat.setTurnoTextoEnCurso(true);
            chat.setTurnoTextoIniciadoEn(LocalDateTime.now());
            repoChat.saveAndFlush(chat);
        });

        textoTurnoService.recuperarPendientesPeriodicamente();
        Thread.sleep(100);
        verifyNoInteractions(cola);
        verify(proveedor, never()).consultarContinuacion(anyString(), anyString(), any());

        db.update("UPDATE chat SET turno_texto_iniciado_en=CURRENT_TIMESTAMP - INTERVAL '3 minutes' WHERE id=?",
                Long.valueOf(chatId));
        textoTurnoService.recuperarPendientesPeriodicamente();
        esperarEstado(EstadoTurnoTexto.COMPLETADA);
        assertEquals(2, db.queryForObject("SELECT intentos_turno_texto FROM chat WHERE id=?", Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void agotaReintentosTransientYBloqueaNuevosMensajes() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            throw new ProveedorIaException(new SocketTimeoutException("synthetic timeout"), true);
        });
        String solicitudId = UUID.randomUUID().toString();
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudId);
        esperarEstado(EstadoTurnoTexto.NO_DISPONIBLE);

        var terminal = chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(2, terminal.intentosTurnoTexto());
        assertFalse(terminal.finalizado(), "fallar el proveedor no representa cierre clínico");
        assertThrows(ConflictoDeEstadoException.class,
                () -> textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Otra cosa", UUID.randomUUID().toString()));
        assertEquals(2, llamadas.get());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void errorTransitorioSeReintentaUnaVezYContinuaConLaRespuesta() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            if (llamadas.incrementAndGet() == 1) {
                throw new ProveedorIaException(new SocketTimeoutException("synthetic timeout"), true);
            }
            return new TriageAiResponse(false, "¿Desde cuándo empezó?", null);
        });
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", UUID.randomUUID().toString());
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        assertEquals(2, llamadas.get());
        assertEquals(2, chatService.obtenerChat(chatId, pacienteAuth).intentosTurnoTexto());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void errorNoTransitorioSeDetieneEnElPrimerIntento() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            throw new ProveedorIaException(new IllegalArgumentException("invalid response"), false);
        });
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", UUID.randomUUID().toString());
        esperarEstado(EstadoTurnoTexto.NO_DISPONIBLE);

        assertEquals(1, llamadas.get());
        assertEquals(1, chatService.obtenerChat(chatId, pacienteAuth).intentosTurnoTexto());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void dosTimeoutsEnClasificacionFinalConservanFALLIDAYSinResultadoNiCierreBot() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chat chat = repoChat.findById(Long.valueOf(chatId)).orElseThrow();
            chat.agregarMensaje(new Mensaje("Tengo dolor de garganta desde ayer.", AutorMensaje.PACIENTE, chat.getPaciente()));
            chat.agregarMensaje(new Mensaje("¿Cómo evolucionó?", AutorMensaje.BOT, null));
            chat.agregarMensaje(new Mensaje("Empeoró un poco, intensidad 5 de 10.", AutorMensaje.PACIENTE, chat.getPaciente()));
            chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
            repoChat.saveAndFlush(chat);
        });
        int pacientesAntes = db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId));
        int botsAntes = db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'",
                Integer.class, Long.valueOf(chatId));
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultar(anyString(), anyString(), eq(true))).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            throw new ProveedorIaException(new SocketTimeoutException("synthetic final timeout"), true);
        });

        textoTurnoService.aceptarTurno(chatId, pacienteAuth,
                "No tengo fiebre ni dificultad para respirar. No tengo enfermedades previas, alergias ni medicación. No, no tengo más síntomas.",
                UUID.randomUUID().toString());
        esperarEstado(EstadoTurnoTexto.NO_DISPONIBLE);

        var terminal = chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(2, llamadas.get());
        assertEquals(2, terminal.intentosTurnoTexto());
        assertTrue(terminal.finalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, terminal.estadoClasificacion());
        assertNull(db.queryForObject("SELECT resultado_triage_json FROM chat WHERE id=?", String.class, Long.valueOf(chatId)));
        assertEquals(pacientesAntes + 1, db.queryForObject(
                "SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'", Integer.class, Long.valueOf(chatId)));
        assertEquals(botsAntes, db.queryForObject(
                "SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'", Integer.class, Long.valueOf(chatId)));
        verifyNoInteractions(cola);
    }

    private void esperarEstado(EstadoTurnoTexto estadoEsperado) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        EstadoTurnoTexto estado = EstadoTurnoTexto.PENDIENTE;
        while (estado != estadoEsperado && System.nanoTime() < limite) {
            estado = chatService.obtenerChat(chatId, pacienteAuth).estadoTurnoTexto();
            if (estado != estadoEsperado) Thread.sleep(20);
        }
        assertEquals(estadoEsperado, estado);
    }
}
