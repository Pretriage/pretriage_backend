package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Autowired RepoConsultasMedicas repoConsultas;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean TriageIaClient proveedor;
    @MockitoBean AtencionHospitalService cola;
    private String pacienteAuth;
    private String chatId;
    private Long consultaId;

    @BeforeEach
    void prepararChat() {
        ReflectionTestUtils.setField(textoTurnoService, "demoraTextoMs", 10L);
        ReflectionTestUtils.setField(textoTurnoService, "demoraClasificacionMs", 10L);
        pacienteAuth = "test-text-turn|" + UUID.randomUUID();
        db.update("INSERT INTO usuario_auth (id, nombre, apellido, numero_documento, tipo_documento, correo_electronico) VALUES (?, 'Paciente', 'Prueba', ?, 'DNI', ?)",
                pacienteAuth, UUID.randomUUID().toString(), UUID.randomUUID() + "@example.invalid");
        db.update("INSERT INTO paciente (auth_id) VALUES (?)", pacienteAuth);
        chatId = chatService.iniciarChat(pacienteAuth).id().toString();
    }

    @AfterEach
    void limpiarPaciente() {
        if (consultaId != null) db.update("DELETE FROM consulta_medica WHERE id=?", consultaId);
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
        long versionAntesDePolling = visible.versionEstado();
        for (int i = 0; i < 3; i++) {
            var polled = chatService.obtenerChat(chatId, pacienteAuth);
            assertEquals(solicitudId, polled.solicitudTurnoTextoId());
            assertEquals(EstadoTurnoTexto.PENDIENTE, polled.estadoTurnoTexto());
            assertEquals(versionAntesDePolling, polled.versionEstado(), "GET is observational and must not advance the version");
        }
        assertEquals(1, llamadas.get(), "GET polling must not POST a duplicate provider request");
        var repetido = textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudId);
        assertEquals(solicitudId, repetido.solicitudTurnoTextoId());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(ConflictoDeEstadoException.class,
                () -> textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Mensaje distinto", UUID.randomUUID().toString())));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        liberarProveedor.countDown();
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        assertEquals(1, llamadas.get());
        assertTrue(chatService.obtenerChat(chatId, pacienteAuth).versionEstado() > versionAntesDePolling,
                "completion publishes a later state version");
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
    void recuperaTurnoLegacyActivoSinVolverAInsertarNiReenviarElMensajePaciente() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            return new TriageAiResponse(false, "¿Desde cuándo empezó?", null);
        });
        String solicitudId = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chat chat = repoChat.findById(Long.valueOf(chatId)).orElseThrow();
            ConsultaMedica consulta = new ConsultaMedica();
            consulta.setPaciente(chat.getPaciente());
            consulta.setFechaHoraCreacion(LocalDateTime.now().minusMinutes(2));
            consulta.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);
            consultaId = repoConsultas.saveAndFlush(consulta).getId();
            chat.setConsultaOrigenId(consultaId);
            Mensaje mensaje = new Mensaje("Tengo tos", AutorMensaje.PACIENTE, chat.getPaciente());
            mensaje.setSolicitudId(solicitudId);
            chat.agregarMensaje(mensaje);
            chat.setSolicitudTurnoTextoId(solicitudId);
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.NO_DISPONIBLE);
            chat.setEstadoRecuperacion(com.pretriage.backend.model.chat.EstadoRecuperacionChat.SIN_RECUPERACION);
            chat.setTurnoTextoEnCurso(false);
            repoChat.saveAndFlush(chat);
        });

        textoTurnoService.recuperarPendientesPeriodicamente();
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        assertEquals(1, llamadas.get());
        assertEquals(solicitudId, chatService.obtenerChat(chatId, pacienteAuth).solicitudTurnoTextoId());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND solicitud_id=?",
                Integer.class, Long.valueOf(chatId), solicitudId));
    }

    @Test
    void consultaOrigenCanceladaAbandonaTurnoEnEstadoParcialSinLlamarAlProveedor() throws Exception {
        String solicitudId = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chat chat = repoChat.findById(Long.valueOf(chatId)).orElseThrow();
            ConsultaMedica consulta = new ConsultaMedica();
            consulta.setPaciente(chat.getPaciente());
            consulta.setFechaHoraCreacion(LocalDateTime.now().minusMinutes(2));
            consulta.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);
            consultaId = repoConsultas.saveAndFlush(consulta).getId();
            chat.setConsultaOrigenId(consultaId);
            Mensaje paciente = new Mensaje("Tengo tos", AutorMensaje.PACIENTE, chat.getPaciente());
            paciente.setSolicitudId(solicitudId);
            chat.agregarMensaje(paciente);
            chat.setSolicitudTurnoTextoId(solicitudId);
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.PENDIENTE);
            chat.setEstadoRecuperacion(com.pretriage.backend.model.chat.EstadoRecuperacionChat.REINTENTANDO);
            chat.setProximoReintentoEn(LocalDateTime.now().plusMinutes(2));
            repoChat.saveAndFlush(chat);
        });
        db.update("UPDATE consulta_medica SET estado_consulta='CANCELADA' WHERE id=?", consultaId);
        db.update("UPDATE chat SET proximo_reintento_en=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", Long.valueOf(chatId));

        textoTurnoService.recuperarPendientesPeriodicamente();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var actual = chatService.obtenerChat(chatId, pacienteAuth);
        while ((!actual.finalizado() || !actual.cierreParcial()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            actual = chatService.obtenerChat(chatId, pacienteAuth);
        }

        assertTrue(actual.finalizado());
        assertTrue(actual.cierreParcial());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.PARCIAL, actual.estadoClasificacion());
        assertEquals(EstadoTurnoTexto.COMPLETADA, actual.estadoTurnoTexto());
        assertNull(db.queryForObject("SELECT resultado_triage_json FROM chat WHERE id=?", String.class, Long.valueOf(chatId)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertNull(repoConsultas.findById(consultaId).orElseThrow().getChat(), "do not link a cancelled consultation");
        verifyNoInteractions(proveedor);
        verifyNoInteractions(cola);
    }

    @Test
    void clasificacionTardiaEnriqueceConsultaEnAtencionSinVolverAIngresarlaACola() throws Exception {
        String solicitudId = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chat chat = repoChat.findById(Long.valueOf(chatId)).orElseThrow();
            ConsultaMedica consulta = new ConsultaMedica();
            consulta.setPaciente(chat.getPaciente());
            consulta.setFechaHoraCreacion(LocalDateTime.now().minusMinutes(2));
            consulta.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);
            consultaId = repoConsultas.saveAndFlush(consulta).getId();
            chat.setConsultaOrigenId(consultaId);
            chat.agregarMensaje(new Mensaje("Tengo un moratón en la rodilla desde hace tres días y empeora.",
                    AutorMensaje.PACIENTE, chat.getPaciente()));
            chat.agregarMensaje(new Mensaje("¿Desde cuándo empezó?", AutorMensaje.BOT, null));
            chat.agregarMensaje(new Mensaje("Desde hace tres días, empeora y duele 8 de 10.",
                    AutorMensaje.PACIENTE, chat.getPaciente()));
            chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?",
                    AutorMensaje.BOT, null));
            repoChat.saveAndFlush(chat);
        });

        CountDownLatch clasificadorIniciado = new CountDownLatch(1);
        CountDownLatch liberarClasificador = new CountDownLatch(1);
        var resultado = new TriageResultDTO("moretón en rodilla", java.util.List.of("moretón"),
                "hace 3 días", "empeora", 8, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), "no informado", "no informado", 3, false, "Evaluación presencial");
        when(proveedor.consultar(anyString(), anyString(), eq(true))).thenAnswer(invocation -> {
            clasificadorIniciado.countDown();
            assertTrue(liberarClasificador.await(10, TimeUnit.SECONDS));
            return new TriageAiResponse(true, "Listo.", resultado);
        });

        try {
            var aceptado = textoTurnoService.aceptarTurno(chatId, pacienteAuth,
                    "No tengo dificultad para respirar ni dolor de pecho. No tengo antecedentes ni alergias. "
                            + "No, no tengo más síntomas.", solicitudId);
            assertEquals(EstadoTurnoTexto.PENDIENTE, aceptado.estadoTurnoTexto());
            assertTrue(clasificadorIniciado.await(10, TimeUnit.SECONDS));

            db.update("UPDATE consulta_medica SET estado_consulta='EN_ATENCION' WHERE id=?", consultaId);
        } finally {
            liberarClasificador.countDown();
        }

        esperarClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA);
        var chatFinal = chatService.obtenerChat(chatId, pacienteAuth);
        var consultaFinal = repoConsultas.findById(consultaId).orElseThrow();
        String resultadoGuardado = db.queryForObject(
                "SELECT resultado_triage_json FROM chat WHERE id=?", String.class, Long.valueOf(chatId));
        assertTrue(chatFinal.finalizado());
        assertNotNull(resultadoGuardado);
        assertEquals(EstadoConsulta.EN_ATENCION, consultaFinal.getEstadoConsulta());
        assertEquals(chatId, consultaFinal.getChat().getId().toString());
        assertEquals(resultadoGuardado, consultaFinal.getResumenPretriageJson());
        assertEquals(com.pretriage.backend.model.consultas.NivelDeGravedad.URGENTE,
                consultaFinal.getNivelDeGravedadBot());
        verifyNoInteractions(cola);
    }

    @Test
    void outageEsperaBackoffSinConsumirFallosInternosNiRepostearEnPolling() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            throw new ProveedorIaException(new SocketTimeoutException("synthetic timeout"), true);
        });
        String solicitudId = UUID.randomUUID().toString();
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", solicitudId);
        esperarEstado(EstadoTurnoTexto.NO_DISPONIBLE);

        var esperando = chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(1, esperando.intentosTurnoTexto());
        assertEquals(com.pretriage.backend.model.chat.EstadoRecuperacionChat.ESPERANDO_API, esperando.estadoRecuperacion());
        assertNotNull(esperando.proximoReintentoEn());
        assertTrue(esperando.proximoReintentoEn().isAfter(java.time.Instant.now().plusSeconds(20)));
        assertFalse(esperando.finalizado());
        assertEquals(0, db.queryForObject("SELECT fallos_internos_turno_texto FROM chat WHERE id=?", Integer.class, Long.valueOf(chatId)));
        textoTurnoService.recuperarPendientesPeriodicamente();
        for (int i = 0; i < 3; i++) chatService.obtenerChat(chatId, pacienteAuth);
        Thread.sleep(100);
        assertEquals(1, llamadas.get(), "GET and recovery scans respect the persisted backoff deadline");
        assertThrows(ConflictoDeEstadoException.class,
                () -> textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Otra cosa", UUID.randomUUID().toString()));
        assertEquals(1, llamadas.get());
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
        esperarEstado(EstadoTurnoTexto.NO_DISPONIBLE);
        assertEquals(1, chatService.obtenerChat(chatId, pacienteAuth).intentosTurnoTexto());
        db.update("UPDATE chat SET proximo_reintento_en=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", Long.valueOf(chatId));
        textoTurnoService.recuperarPendientesPeriodicamente();
        esperarEstado(EstadoTurnoTexto.COMPLETADA);

        assertEquals(2, llamadas.get());
        assertEquals(2, chatService.obtenerChat(chatId, pacienteAuth).intentosTurnoTexto());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void rechazosInternosAgotanDosIntentosYCierranParcialSinInventarResultado() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        when(proveedor.consultarContinuacion(anyString(), anyString(), any())).thenAnswer(invocation -> {
            llamadas.incrementAndGet();
            throw new ProveedorIaException(new IllegalArgumentException("invalid response"),
                    ProveedorIaException.Categoria.VALIDACION_RESPUESTA);
        });
        when(proveedor.consultar(anyString(), anyString(), eq(true))).thenThrow(
                new ProveedorIaException(new IllegalArgumentException("invalid final response"),
                        ProveedorIaException.Categoria.VALIDACION_RESPUESTA));
        textoTurnoService.aceptarTurno(chatId, pacienteAuth, "Tengo tos", UUID.randomUUID().toString());
        esperarClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat.PARCIAL);

        var terminal = chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(2, llamadas.get());
        assertEquals(2, terminal.intentosTurnoTexto());
        assertTrue(terminal.finalizado());
        assertTrue(terminal.cierreParcial());
        assertNull(db.queryForObject("SELECT resultado_triage_json FROM chat WHERE id=?", String.class, Long.valueOf(chatId)));
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.PARCIAL, terminal.estadoClasificacion());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, Long.valueOf(chatId)));
    }

    @Test
    void indisponibilidadEnClasificacionFinalQuedaDurableEnBackoffSinReclasificarEnGet() throws Exception {
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
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var terminal = chatService.obtenerChat(chatId, pacienteAuth);
        while (terminal.estadoClasificacion() != com.pretriage.backend.model.chat.EstadoClasificacionChat.PENDIENTE
                || terminal.estadoRecuperacion() != com.pretriage.backend.model.chat.EstadoRecuperacionChat.ESPERANDO_API) {
            if (System.nanoTime() >= limite) break;
            Thread.sleep(20);
            terminal = chatService.obtenerChat(chatId, pacienteAuth);
        }

        assertEquals(1, llamadas.get());
        assertEquals(EstadoTurnoTexto.COMPLETADA, terminal.estadoTurnoTexto());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.PENDIENTE, terminal.estadoClasificacion());
        assertEquals(com.pretriage.backend.model.chat.EstadoRecuperacionChat.ESPERANDO_API, terminal.estadoRecuperacion());
        assertTrue(terminal.proximoReintentoEn().isAfter(java.time.Instant.now().plusSeconds(20)));
        assertTrue(terminal.finalizado());
        assertNull(db.queryForObject("SELECT resultado_triage_json FROM chat WHERE id=?", String.class, Long.valueOf(chatId)));
        assertEquals(pacientesAntes + 1, db.queryForObject(
                "SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'", Integer.class, Long.valueOf(chatId)));
        assertEquals(botsAntes, db.queryForObject(
                "SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='BOT'", Integer.class, Long.valueOf(chatId)));
        textoTurnoService.recuperarPendientesPeriodicamente();
        chatService.obtenerChat(chatId, pacienteAuth);
        assertEquals(1, llamadas.get(), "Polling/recovery must not bypass the persisted API backoff");
        verifyNoInteractions(cola);
    }

    private void esperarClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat estadoEsperado) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var actual = chatService.obtenerChat(chatId, pacienteAuth);
        while (actual.estadoClasificacion() != estadoEsperado && System.nanoTime() < limite) {
            Thread.sleep(20);
            actual = chatService.obtenerChat(chatId, pacienteAuth);
        }
        assertEquals(estadoEsperado, actual.estadoClasificacion());
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
