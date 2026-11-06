package com.pretriage.backend;

import com.pretriage.backend.controllers.dtos.EstadoEntradaChat;
import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.EstadoRecuperacionChat;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.repositories.RepoHospitales;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import com.pretriage.backend.services.IngresoColaService;
import com.pretriage.backend.services.TriageIaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Opt-in harness for manually driving the installed mobile app against a disposable QA database.
 * This test intentionally holds the Spring Boot process open until QA_STOP_FILE is created.
 */
@EnabledIfEnvironmentVariable(named = "RUN_MOBILE_CHAT_QA", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1",
        "server.port=18080",
        "spring.docker.compose.skip.in-tests=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.format_sql=false",
        "chat.recuperacion.scan-ms=1000",
        "chat.recuperacion.legacy-activos=true",
        "chat.recuperacion.texto-demora-ms=3000",
        "chat.recuperacion.clasificacion-demora-ms=3000",
        "chat.recuperacion.api-demora-inicial-ms=3000",
        "pretriage.ia.gemini.api-key=",
        "pretriage.voz.gemini.api-key=",
        "pretriage.storage.s3.enabled=false",
        "aws.accessKeyId=",
        "aws.secretAccessKey=",
        "aws.s3.region="
})
class MobileChatRecoveryQaTest {
    private static final String AUTH_ID = "qa-mobile-recovery";
    private static final Pattern DB_NAME = Pattern.compile(
            "pretriage_qa_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern MODE_PATTERN = Pattern.compile("\"mode\"\\s*:\\s*\"(normal|interno|api)\"");

    private static String jdbcUrl;
    private static String dbUsername;
    private static String dbPassword;
    private static String readyFile;
    private static String stopFile;
    private static String controlFile;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = requiredEnv("QA_JDBC_URL");
        String databaseName = jdbcUrl.startsWith("jdbc:postgresql://")
                ? jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1).split("\\?", 2)[0]
                : "";
        if (!DB_NAME.matcher(databaseName).matches()) {
            throw new IllegalStateException("QA_JDBC_URL debe apuntar exclusivamente a una base pretriage_qa_UUID");
        }
        dbUsername = requiredEnv("QA_DB_USERNAME");
        dbPassword = requiredEnv("QA_DB_PASSWORD");
        readyFile = requiredEnv("QA_READY_FILE");
        stopFile = requiredEnv("QA_STOP_FILE");
        controlFile = requiredEnv("QA_CONTROL_FILE");

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> dbUsername);
        registry.add("spring.datasource.password", () -> dbPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.docker.compose.skip.in-tests", () -> "true");
    }

    @Autowired RepoPacientes repoPacientes;
    @Autowired RepoEspecialidadesMedicas repoEspecialidades;
    @Autowired RepoHospitales repoHospitales;
    @Autowired RepoSectores repoSectores;
    @Autowired RepoSalas repoSalas;
    @Autowired RepoConsultasMedicas repoConsultas;
    @Autowired RepoEntradasCola repoEntradas;
    @Autowired RepoChat repoChats;
    @Autowired IngresoColaService ingresoColaService;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TriageIaClient triageIaClient;

    private final AtomicInteger apiFailuresForCurrentMode = new AtomicInteger();
    private volatile String lastObservedMode = "normal";

    @BeforeEach
    void configureOnlyTestDoubles() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String receivedToken = invocation.getArgument(0, String.class);
            Instant now = Instant.now();
            return Jwt.withTokenValue(receivedToken)
                    .header("alg", "none")
                    .claim("sub", AUTH_ID)
                    .claim("iss", environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    .claim("scope", "openid profile")
                    .claim("roles", List.of("USER"))
                    .issuedAt(now.minusSeconds(1))
                    .expiresAt(now.plus(Duration.ofHours(2)))
                    .build();
        });

        doAnswer(invocation -> responderForQuestion(invocation.getArgument(1, String.class)))
                .when(triageIaClient).consultarContinuacion(anyString(), anyString(), any(Duration.class));
        doAnswer(invocation -> responderForClassification())
                .when(triageIaClient).consultar(anyString(), anyString(), anyBoolean());
        doAnswer(invocation -> responderForClassification())
                .when(triageIaClient).consultar(anyString(), anyString(), anyBoolean(), any(Duration.class));
        doAnswer(invocation -> responderForClassification())
                .when(triageIaClient).consultar(anyString(), anyString());
        doAnswer(invocation -> responderForClassification())
                .when(triageIaClient).consultarCorreccionFinal(anyString(), anyString(), any(Duration.class));
        when(triageIaClient.consultarAlcance(anyString(), any(Duration.class)))
                .thenReturn(EstadoEntradaChat.RELEVANTE);
    }

    @Test
    void iniciarApiLocalYEsperarSenalDeParada() throws Exception {
        Files.createDirectories(Path.of(readyFile).toAbsolutePath().getParent());
        Files.createDirectories(Path.of(stopFile).toAbsolutePath().getParent());
        Files.createDirectories(Path.of(controlFile).toAbsolutePath().getParent());
        Files.deleteIfExists(Path.of(stopFile));
        Files.deleteIfExists(Path.of(readyFile));
        if (!Files.exists(Path.of(controlFile))) {
            Files.writeString(Path.of(controlFile), "{\"mode\":\"normal\"}\n", StandardCharsets.UTF_8);
        }

        Fixture fixture = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> crearFixtureReal());
        validarAPIsHome(fixture);
        escribirReady(fixture);

        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(Path.of(stopFile))) return;
            Thread.sleep(10_000);
        }
        throw new IllegalStateException("QA móvil superó el límite de 20 minutos; faltó crear QA_STOP_FILE");
    }

    private Fixture crearFixtureReal() {
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId(AUTH_ID);
        usuario.setNombre("QA Mobile");
        usuario.setApellido("Recovery");
        usuario.setNumeroDocumento("QA-" + UUID.randomUUID());
        usuario.setTipoDocumento(TipoDocumento.DNI);
        usuario.setCorreoElectronico("qa-mobile-recovery@example.invalid");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);
        paciente.setNombre(usuario.getNombre());
        paciente.setApellido(usuario.getApellido());
        paciente.setNumeroDocumento(usuario.getNumeroDocumento());
        paciente.setTipoDocumento(TipoDocumento.DNI);
        paciente.setCorreoElectronico(usuario.getCorreoElectronico());
        paciente = repoPacientes.saveAndFlush(paciente);

        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setCodigo("QA-RECOVERY-" + UUID.randomUUID().toString().substring(0, 8));
        especialidad.setNombre("QA Recovery");
        especialidad = repoEspecialidades.saveAndFlush(especialidad);

        Hospital hospital = new Hospital();
        hospital.setPlaceId("qa-mobile-recovery-" + UUID.randomUUID());
        hospital.setNombre("Hospital QA Mobile Recovery");
        hospital.setEspecialidades(List.of(especialidad));
        hospital = repoHospitales.saveAndFlush(hospital);

        Sector sector = new Sector();
        sector.setNombre("QA Recovery");
        sector.setActiva(true);
        sector.setHospital(hospital);
        sector.setEspecialidad(especialidad);
        sector = repoSectores.saveAndFlush(sector);

        Sala sala = new Sala();
        sala.setNombre("QA-R1");
        sala.setActiva(true);
        sala.setHospital(hospital);
        sala.setSector(sector);
        sala.setEspecialidad(especialidad);
        sala = repoSalas.saveAndFlush(sala);

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setFechaHoraCreacion(LocalDateTime.now().minusSeconds(5));
        consulta.setPaciente(paciente);
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);
        consulta.setSector(sector);
        consulta.setEstadoConsulta(EstadoConsulta.PRETRIAGE_EN_PROCESO);
        consulta.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        consulta = repoConsultas.saveAndFlush(consulta);
        ingresoColaService.ingresar(consulta, NivelDeGravedad.NORMAL, sector);
        consulta = repoConsultas.findById(consulta.getId()).orElseThrow();
        var entrada = repoEntradas.findByConsultaMedicaId(consulta.getId()).orElseThrow();
        assertEquals(EstadoEntradaCola.EN_COLA, entrada.getEstado());

        Chat chat = crearChatLegacy(paciente, consulta);
        return new Fixture(paciente, hospital, especialidad, sector, sala, consulta, entrada, chat);
    }

    private Chat crearChatLegacy(Paciente paciente, ConsultaMedica consulta) {
        long configuredId = Long.parseLong(System.getenv().getOrDefault("QA_CHAT_ID", "0"));
        Chat chat;
        if (configuredId > 0) {
            if (repoChats.existsById(configuredId)) {
                throw new IllegalStateException("QA_CHAT_ID ya existe en la base QA recién creada");
            }
            jdbc.update("INSERT INTO chat (id, fecha_hora_creacion, paciente_id, finalizado) " +
                            "OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, false)",
                    configuredId, LocalDateTime.now(), paciente.getId());
            jdbc.execute("SELECT setval(pg_get_serial_sequence('chat', 'id'), " +
                    "GREATEST((SELECT COALESCE(MAX(id), 1) FROM chat), 1), true)");
            chat = repoChats.findById(configuredId).orElseThrow();
        } else {
            chat = new Chat(paciente);
        }
        chat.setConsultaOrigenId(consulta.getId());
        chat.setFinalizado(false);
        chat.setEstadoClasificacion(EstadoClasificacionChat.NO_SOLICITADA);
        chat.setEstadoTurnoTexto(EstadoTurnoTexto.NO_DISPONIBLE);
        chat.setSolicitudTurnoTextoId(UUID.randomUUID().toString());
        chat.setIntentosTurnoTexto(1);
        chat.setFallosInternosTurnoTexto(0);
        chat.setFallosDisponibilidad(0);
        chat.setTurnoTextoEnCurso(false);
        chat.setErrorTurnoTexto("respuesta_no_valida");
        chat.setEstadoRecuperacion(EstadoRecuperacionChat.SIN_RECUPERACION);
        chat.setProximoReintentoEn(null);
        chat.setRecuperacionLegacyAplicada(false);
        Mensaje mensaje = new Mensaje("QA legacy: me duele la panza", AutorMensaje.PACIENTE, paciente);
        mensaje.setSolicitudId(chat.getSolicitudTurnoTextoId());
        chat.agregarMensaje(mensaje);
        return repoChats.saveAndFlush(chat);
    }

    private void validarAPIsHome(Fixture fixture) throws Exception {
        String bearer = "Bearer qa-mobile-recovery-test-token";
        assertEquals(200, request("/api/chat/" + fixture.chat().getId(), bearer).status());
        assertEquals(200, request("/api/paciente/consulta/estado", bearer).status());
        assertEquals(200, request("/api/atencion/hospital", bearer).status());
        assertEquals(200, request("/api/atencion/tiempo-estimado", bearer).status());

        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://127.0.0.1:18080/api/atencion/tiempos/suscribirse/" + fixture.consulta().getId())
                .toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", bearer);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try {
            assertEquals(200, connection.getResponseCode());
            assertTrue(connection.getContentType().startsWith("text/event-stream"));
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("event:tiempo-estimado", reader.readLine().replace(" ", ""));
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpResponse request(String path, String bearer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:18080" + path)
                .toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", bearer);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try {
            return new HttpResponse(connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private synchronized TriageAiResponse responderForQuestion(String conversation) {
        String mode = currentMode();
        if ("api".equals(mode) && apiFailuresForCurrentMode.getAndIncrement() == 0) {
            throw new ProveedorIaException(new java.net.SocketTimeoutException("QA synthetic timeout"),
                    ProveedorIaException.Categoria.API_NO_DISPONIBLE, 503, Duration.ofSeconds(8));
        }
        if ("interno".equals(mode)) {
            return new TriageAiResponse(false,
                    "¿Desde cuándo te duele la cabeza y qué intensidad tiene?", null, EstadoEntradaChat.RELEVANTE);
        }
        String question = conversation.contains("\"dimension\":\"INTENSIDAD\"")
                ? "¿Qué intensidad tiene el dolor?"
                : conversation.contains("\"tipo\":\"INVITACION\"")
                    ? "¿Hay algo más que quieras contar sobre lo que te pasa?"
                    : "¿Desde cuándo te duele?";
        return new TriageAiResponse(false, question, null, EstadoEntradaChat.RELEVANTE);
    }

    private synchronized TriageAiResponse responderForClassification() {
        if ("interno".equals(currentMode())) {
            throw new ProveedorIaException(new IllegalStateException("QA synthetic invalid classification"),
                    ProveedorIaException.Categoria.VALIDACION_RESPUESTA);
        }
        TriageResultDTO result = new TriageResultDTO(
                "dolor abdominal", List.of("dolor abdominal"), "no informado", "no informado", null,
                List.of(), List.of(), List.of(), List.of(), "no aplica", "QA synthetic", 2, false, "");
        return new TriageAiResponse(true, "Gracias, ya registré tus respuestas.", result,
                EstadoEntradaChat.RELEVANTE);
    }

    private String currentMode() {
        String content;
        try {
            content = Files.readString(Path.of(controlFile), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "normal";
        }
        Matcher matcher = MODE_PATTERN.matcher(content);
        String mode = matcher.find() ? matcher.group(1) : "normal";
        if (!mode.equals(lastObservedMode)) {
            lastObservedMode = mode;
            apiFailuresForCurrentMode.set(0);
        }
        return mode;
    }

    private void escribirReady(Fixture f) throws Exception {
        String ready = "{\n" +
                "  \"state\": \"READY\",\n" +
                "  \"baseUrl\": \"http://127.0.0.1:18080\",\n" +
                "  \"chatId\": \"" + f.chat().getId() + "\",\n" +
                "  \"consultaId\": " + f.consulta().getId() + ",\n" +
                "  \"hospitalId\": " + f.hospital().getId() + ",\n" +
                "  \"especialidadCodigo\": \"" + f.especialidad().getCodigo() + "\",\n" +
                "  \"sectorId\": " + f.sector().getId() + ",\n" +
                "  \"salaId\": " + f.sala().getId() + ",\n" +
                "  \"jwtSubject\": \"" + AUTH_ID + "\",\n" +
                "  \"controlFile\": \"" + escape(controlFile) + "\",\n" +
                "  \"stopFile\": \"" + escape(stopFile) + "\",\n" +
                "  \"modes\": [\"normal\", \"interno\", \"api\"]\n" +
                "}\n";
        Path destination = Path.of(readyFile);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, ready, StandardCharsets.UTF_8);
        Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " debe configurarse para este QA opt-in");
        return value;
    }

    private record Fixture(Paciente paciente, Hospital hospital, EspecialidadMedica especialidad,
                           Sector sector, Sala sala, ConsultaMedica consulta,
                           com.pretriage.backend.model.consultas.EntradaCola entrada, Chat chat) {}

    private record HttpResponse(int status) {}
}
