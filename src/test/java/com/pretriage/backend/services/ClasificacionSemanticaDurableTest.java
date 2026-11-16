package com.pretriage.backend.services;

import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.repositories.RepoGestoresDeColas;
import com.pretriage.backend.repositories.RepoHospitales;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Durable semantic-correction checks; run only through scripts/test_backend_isolated.py. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClasificacionSemanticaDurableTest {
    @Autowired ChatService chatService;
    @Autowired TextoTurnoService textoTurnoService;
    @Autowired RepoChat repoChat;
    @Autowired RepoPacientes repoPacientes;
    @Autowired RepoConsultasMedicas repoConsultas;
    @Autowired RepoEntradasCola repoEntradas;
    @Autowired RepoEspecialidadesMedicas repoEspecialidades;
    @Autowired RepoHospitales repoHospitales;
    @Autowired RepoSectores repoSectores;
    @Autowired RepoSalas repoSalas;
    @Autowired RepoGestoresDeColas repoGestores;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean GeminiJsonClient gemini;
    @MockitoBean GooglePlacesService googlePlacesService;

    private String pacienteAuth;
    private Long pacienteId;
    private Long chatId;
    private Long consultaId;
    private Long especialidadId;
    private Long hospitalId;
    private Long sectorId;
    private Long salaId;
    private Long gestorId;
    private Long entradaId;

    record CasoAlarma(String texto, String descripcion, int prioridad) { }

    static Stream<CasoAlarma> casosConAlarma() {
        return Stream.of(
                new CasoAlarma("Tengo dolor de pecho", "dolor de pecho", 4),
                new CasoAlarma("Tengo un sangrado abundante", "sangrado abundante", 4));
    }

    @BeforeEach
    void prepararConsultaEnColaYChat() {
        ReflectionTestUtils.setField(textoTurnoService, "demoraTextoMs", 10L);
        ReflectionTestUtils.setField(textoTurnoService, "demoraClasificacionMs", 10L);
        pacienteAuth = "test-clasificacion-semantica|" + UUID.randomUUID();
        db.update("INSERT INTO usuario_auth (id, nombre, apellido, numero_documento, tipo_documento, correo_electronico) "
                        + "VALUES (?, 'Paciente', 'Prueba', ?, 'DNI', ?)",
                pacienteAuth, UUID.randomUUID().toString(), UUID.randomUUID() + "@example.invalid");
        db.update("INSERT INTO paciente (auth_id) VALUES (?)", pacienteAuth);
        Paciente paciente = repoPacientes.findByUsuarioAuthId(pacienteAuth).orElseThrow();
        pacienteId = paciente.getId();

        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setCodigo("SEMANTICA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        especialidad.setNombre("Clínica médica");
        especialidad = repoEspecialidades.saveAndFlush(especialidad);
        especialidadId = especialidad.getId();

        Hospital hospital = new Hospital();
        hospital.setPlaceId("semantic-test-" + UUID.randomUUID());
        hospital.setNombre("Hospital de prueba semántica");
        hospital.setEspecialidades(List.of(especialidad));
        hospital = repoHospitales.saveAndFlush(hospital);
        hospitalId = hospital.getId();

        Sector sector = new Sector();
        sector.setNombre("Guardia");
        sector.setHospital(hospital);
        sector.setEspecialidad(especialidad);
        sector = repoSectores.saveAndFlush(sector);
        sectorId = sector.getId();

        Sala sala = new Sala();
        sala.setNombre("Sala de guardia");
        sala.setHospital(hospital);
        sala.setEspecialidad(especialidad);
        sala.setSector(sector);
        sala = repoSalas.saveAndFlush(sala);
        salaId = sala.getId();

        Chat chat = new Chat();
        chat.setPaciente(paciente);
        chat.setFechaHoraCreacion(LocalDateTime.now());
        chat.agregarMensaje(new Mensaje("¡Hola! Soy el asistente de PreTriage.", AutorMensaje.BOT, null));
        chat = repoChat.saveAndFlush(chat);
        chatId = chat.getId();

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setPaciente(paciente);
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);
        consulta.setSector(sector);
        consulta.setSala(sala);
        consulta.setFechaHoraCreacion(LocalDateTime.now().minusMinutes(1));
        consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        consulta.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        consulta.setChat(chat);
        consulta = repoConsultas.saveAndFlush(consulta);
        consultaId = consulta.getId();
        chat.setConsultaOrigenId(consultaId);
        repoChat.saveAndFlush(chat);

        GestorDeCola gestor = new GestorDeCola();
        gestor.setHospital(hospital);
        gestor.setEspecialidad(especialidad);
        gestor.setSector(sector);
        gestor.agregarConsultaMedicaALaCola(consulta);
        gestor = repoGestores.saveAndFlush(gestor);
        gestorId = gestor.getId();

        EntradaCola entrada = new EntradaCola();
        entrada.setGestorDeCola(gestor);
        entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        entrada.setPrioridad(2);
        entrada.setOrdenRelativo(1L);
        entrada.setFechaHoraIngreso(LocalDateTime.now().minusMinutes(1));
        entradaId = repoEntradas.saveAndFlush(entrada).getId();
    }

    @AfterEach
    void limpiarFixture() {
        if (consultaId != null) db.update("DELETE FROM entrada_cola WHERE id_consulta_medica=?", consultaId);
        if (consultaId != null) db.update("DELETE FROM consulta_medica WHERE id=?", consultaId);
        if (chatId != null) {
            db.update("DELETE FROM mensaje WHERE chat_id=?", chatId);
            db.update("DELETE FROM chat WHERE id=?", chatId);
        }
        if (gestorId != null) db.update("DELETE FROM gestor_de_cola WHERE id=?", gestorId);
        if (salaId != null) db.update("DELETE FROM sala WHERE id=?", salaId);
        if (sectorId != null) db.update("DELETE FROM sector WHERE id=?", sectorId);
        if (hospitalId != null) {
            db.update("DELETE FROM hospital_especialidad_medica WHERE id_hospital=?", hospitalId);
            db.update("DELETE FROM hospital WHERE id=?", hospitalId);
        }
        if (especialidadId != null) db.update("DELETE FROM especialidad_medica WHERE id=?", especialidadId);
        if (pacienteAuth != null) {
            db.update("DELETE FROM paciente WHERE auth_id=?", pacienteAuth);
            db.update("DELETE FROM usuario_auth WHERE id=?", pacienteAuth);
        }
    }

    @ParameterizedTest
    @MethodSource("casosConAlarma")
    void correccionPorInferenciaConservaLaAlarmaYActualizaLaEntradaExistente(CasoAlarma caso) throws Exception {
        AtomicInteger verificaciones = new AtomicInteger();
        String respuestaGenerada = respuestaFinal(caso);
        when(gemini.generar(anyString(), anyString(), anyMap(), any(Duration.class))).thenAnswer(invocation ->
                respuestaProveedor(invocation.getArgument(0), respuestaGenerada, verificaciones));

        String solicitudId = UUID.randomUUID().toString();
        var aceptado = textoTurnoService.aceptarTurno(chatId.toString(), pacienteAuth, caso.texto(), solicitudId);
        assertEquals(EstadoTurnoTexto.PENDIENTE, aceptado.estadoTurnoTexto());

        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var chat = chatService.obtenerChat(chatId.toString(), pacienteAuth);
        while (chat.estadoClasificacion() != EstadoClasificacionChat.COMPLETADA && System.nanoTime() < limite) {
            Thread.sleep(25);
            chat = chatService.obtenerChat(chatId.toString(), pacienteAuth);
        }

        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.estadoClasificacion());
        assertFalse(chat.cierreParcial());
        String resultadoPersistido = repoChat.findById(chatId).orElseThrow().getResultadoTriageJson();
        assertNotNull(resultadoPersistido);
        var resultado = objectMapper.readTree(resultadoPersistido);
        assertEquals(caso.prioridad(), resultado.path("nivelPrioridad").intValue());
        assertTrue(resultado.path("requiereAtencionInmediata").booleanValue());
        assertEquals(caso.descripcion(), resultado.path("signosAlarma").get(0).stringValue());
        assertEquals(1, chat.mensajes().stream().filter(m -> "PACIENTE".equals(m.autor())).count());
        assertEquals(2, verificaciones.get(), "the initial result is rejected and its correction is accepted");

        EntradaCola entradaFinal = repoEntradas.findByConsultaMedicaId(consultaId).orElseThrow();
        assertEquals(entradaId, entradaFinal.getId());
        assertEquals(EstadoEntradaCola.EN_COLA, entradaFinal.getEstado());
        assertEquals(caso.prioridad(), entradaFinal.getPrioridad());
        ConsultaMedica consultaFinal = repoConsultas.findById(consultaId).orElseThrow();
        assertEquals(EstadoConsulta.EN_COLA, consultaFinal.getEstadoConsulta());
        assertEquals(nivelDeGravedadParaPrioridad(caso.prioridad()), consultaFinal.getNivelDeGravedadBot());
        assertEquals(entradaId.longValue(), db.queryForObject(
                "SELECT id FROM entrada_cola WHERE id_consulta_medica=?", Long.class, consultaId));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM entrada_cola WHERE id_consulta_medica=?",
                Integer.class, consultaId));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, chatId));

        clearInvocations(gemini);
        var repetido = textoTurnoService.aceptarTurno(chatId.toString(), pacienteAuth, caso.texto(), solicitudId);
        assertEquals(EstadoTurnoTexto.COMPLETADA, repetido.estadoTurnoTexto());
        verifyNoInteractions(gemini);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mensaje WHERE chat_id=? AND autor='PACIENTE'",
                Integer.class, chatId));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM entrada_cola WHERE id_consulta_medica=?",
                Integer.class, consultaId));
        assertEquals(entradaId, repoEntradas.findByConsultaMedicaId(consultaId).orElseThrow().getId());
    }

    private static String respuestaProveedor(String prompt, String respuestaFinal,
                                             AtomicInteger verificaciones) {
        if (prompt.contains("verificador independiente de una clasificación final")) {
            return verificaciones.incrementAndGet() == 1
                    ? "{\"valido\":false,\"motivo\":\"INFERENCIA\"}"
                    : "{\"valido\":true,\"motivo\":null}";
        }
        return respuestaFinal;
    }

    private static NivelDeGravedad nivelDeGravedadParaPrioridad(int prioridad) {
        return switch (prioridad) {
            case 5 -> NivelDeGravedad.RIESGO_VITAL_INMEDIATO;
            case 4 -> NivelDeGravedad.MUY_URGENTE;
            default -> throw new IllegalArgumentException("El caso debe usar prioridad de alarma");
        };
    }

    private static String respuestaFinal(CasoAlarma caso) {
        String valor = caso.descripcion();
        return "{\"finalizado\":true,"
                + "\"mensaje\":\"Gracias. Ya registré tus respuestas para el equipo médico.\","
                + "\"resultado\":{"
                + "\"motivoConsulta\":\"" + valor + "\","
                + "\"sintomas\":[\"" + valor + "\"],"
                + "\"inicio\":\"no informado\",\"evolucion\":\"no informado\",\"intensidadDolor\":null,"
                + "\"signosAlarma\":[\"" + valor + "\"],\"antecedentesRelevantes\":[],"
                + "\"medicamentos\":[],\"alergias\":[],\"posibilidadEmbarazo\":\"no informado\","
                + "\"observaciones\":\"\",\"nivelPrioridad\":" + caso.prioridad() + ","
                + "\"requiereAtencionInmediata\":true,"
                + "\"recomendacionSeguridad\":\"Busca atención urgente.\"},"
                + "\"estadoEntrada\":\"RELEVANTE\","
                + "\"evidenciaClinica\":["
                + "{\"campo\":\"sintomas\",\"valor\":\"" + valor + "\",\"cita\":\"" + caso.texto() + "\"},"
                + "{\"campo\":\"signosAlarma\",\"valor\":\"" + valor + "\",\"cita\":\"" + caso.texto() + "\"}]}";
    }
}
