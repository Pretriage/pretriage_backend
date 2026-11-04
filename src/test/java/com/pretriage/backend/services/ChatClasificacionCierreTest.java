package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.exceptions.*;
import com.pretriage.backend.model.chat.*;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatClasificacionCierreTest {
    final RepoChat repo = mock(RepoChat.class);
    final TriageIaClient ia = mock(TriageIaClient.class);
    final AtencionHospitalService cola = mock(AtencionHospitalService.class);
    final ChatService service = new ChatService(repo, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
            mock(RepoEntradasCola.class), cola, ia, new ObjectMapper());
    final Chat chat = new Chat(new Paciente());
    ChatClasificacionCierreTest() {
        simularConsultaConPresupuesto();
        chat.setId(1L);
        when(repo.findByIdAndPacienteUsuarioAuthId(1L, "patient")).thenReturn(Optional.of(chat));
        when(repo.findForUpdate(1L, "patient")).thenReturn(Optional.of(chat));
        when(repo.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("patient")).thenReturn(Optional.of(chat));
    }
    private void simularConsultaConPresupuesto() {
        when(ia.consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class)))
                .thenAnswer(invocation -> ia.consultar(invocation.getArgument(0), invocation.getArgument(1), true));
    }
    TriageAiResponse result(int priority, boolean immediate, List<String> alarms, List<String> allergies) {
        return new TriageAiResponse(true, "Gracias", new TriageResultDTO("dolor de pecho", List.of("dolor de pecho"),
                "hoy", "igual", 8, alarms, List.of(), List.of(), allergies, "no informado", "no informado",
                priority, immediate, "Acudir a una guardia"));
    }
    ResumenEntrevistaVoz summary() {
        return new ResumenEntrevistaVoz("dolor de pecho", List.of("dolor de pecho"), "hoy", "igual", 8,
                List.of("dolor toracico"), List.of(), List.of(), List.of(), "no informado", "sin otros datos");
    }
    @Test void textoYVozCorrigenLaMismaIncoherenciaSinCambiarPrioridadLocalmente() {
        for (boolean voice : List.of(false, true)) {
            reset(ia, cola);
            simularConsultaConPresupuesto();
            chat.setFinalizado(false); chat.getMensajes().clear();
            when(ia.consultar(anyString(), anyString(), eq(true))).thenReturn(result(2, false, List.of("dolor toracico"), List.of()));
            when(ia.consultarCorreccionFinal(anyString(), anyString(), any())).thenReturn(result(4, true, List.of("dolor toracico"), List.of()));
            if (voice) service.finalizarEntrevistaVoz("1", "patient", List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de pecho")), summary());
            else service.enviarMensaje("1", "patient", "Tengo dolor de pecho");
            assertTrue(chat.isFinalizado());
            assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
            assertEquals(2, chat.getMensajes().size());
            verify(ia, times(1)).consultarCorreccionFinal(contains("alarma"), anyString(),
                    argThat(plazo -> !plazo.isNegative() && plazo.toSeconds() <= 45));
            verify(cola, times(1)).finalizarTriageEIngresarACola(eq("patient"), any(), anyString(), same(chat));
        }
    }
    @Test void segundoResultadoIncoherenteCierraSinClasificarNiEncolarYNoPermiteOk() {
        var bad = result(2, false, List.of("dolor toracico"), List.of());
        when(ia.consultar(anyString(), anyString(), eq(true))).thenReturn(bad);
        when(ia.consultarCorreccionFinal(anyString(), anyString(), any())).thenReturn(bad);
        assertThrows(ProveedorIaException.class, () -> service.enviarMensaje("1", "patient", "Tengo dolor de pecho"));
        assertEquals(EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertTrue(chat.isFinalizado()); assertNull(chat.getResultadoTriageJson());
        assertEquals(1, chat.getMensajes().size());
        assertThrows(ChatFinalizadoException.class, () -> service.enviarMensaje("1", "patient", "ok"));
        verify(ia, times(1)).consultarCorreccionFinal(anyString(), anyString(), any());
        verifyNoInteractions(cola);
    }
    @Test void ningunaNoSePersisteComoAlergiaYSePideCorreccion() {
        when(ia.consultar(anyString(), anyString(), eq(true))).thenReturn(result(4, true, List.of("dolor toracico"), List.of("Ninguna")));
        when(ia.consultarCorreccionFinal(anyString(), anyString(), any())).thenReturn(result(4, true, List.of("dolor toracico"), List.of()));
        service.enviarMensaje("1", "patient", "Dolor de pecho. No tengo alergias.");
        assertTrue(chat.getResultadoTriageJson().contains("\"alergias\":[]"));
        verify(ia).consultarCorreccionFinal(contains("listas vacias"), anyString(), any());
    }
    @Test void cierraTrasNegarDebilidadRepentinaSinVolverAPedirExploracionDeAlarmas() {
        var historial = List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de cabeza desde anoche"),
                new TurnoVoz(AutorMensaje.BOT, "Del 0 al 10, cuanto te duele?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "6"),
                new TurnoVoz(AutorMensaje.BOT, "Tenes alguna enfermedad?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No"),
                new TurnoVoz(AutorMensaje.BOT, "Sentis debilidad repentina?"));
        for (var turno : historial) chat.agregarMensaje(new Mensaje(turno.contenido(), turno.autor(),
                turno.autor() == AutorMensaje.PACIENTE ? chat.getPaciente() : null));
        var respuesta = new TriageAiResponse(true, "Gracias. Ya registre tus respuestas.",
                new TriageResultDTO("dolor de cabeza", List.of("dolor de cabeza"), "anoche", "no informado", 6,
                        List.of(), List.of(), List.of(), List.of(), "no informado", "no informado",
                        3, false, "Acudi a una guardia si empeora."));
        when(ia.consultar(anyString(), anyString(), eq(true))).thenReturn(respuesta);

        service.enviarMensaje("1", "patient", "No, no tengo otros síntomas");

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        verify(ia, times(1)).consultar(anyString(), anyString(), eq(true));
        verify(ia, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(ia, never()).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(cola, times(1)).finalizarTriageEIngresarACola(eq("patient"), any(), anyString(), same(chat));
    }
    @Test void falloTecnicoNoReintentaAutomaticamenteYConservaEntrevistaCerrada() {
        when(ia.consultar(anyString(), anyString(), eq(true))).thenThrow(new ProveedorIaException(null));
        assertThrows(ProveedorIaException.class, () -> service.finalizarEntrevistaVoz("1", "patient",
                List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de pecho")), summary()));
        assertTrue(chat.isFinalizado()); assertEquals(EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertEquals(1, chat.getMensajes().size());
        service.registrarCierreVozFallido("1", "patient", List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de pecho")));
        assertEquals(1, chat.getMensajes().size());
        verify(ia, never()).consultarCorreccionFinal(anyString(), anyString(), any());
        verifyNoInteractions(cola);
    }

    @Test void cierreVozPorLimiteConEntradaAmbiguaNoCreaClasificacionNiCola() {
        var turnos = java.util.stream.IntStream.range(0, 24)
                .mapToObj(i -> new TurnoVoz(AutorMensaje.PACIENTE, "No sé cómo explicarlo"))
                .toList();
        when(ia.consultarAlcance(anyString(), any())).thenReturn(EstadoEntradaChat.AMBIGUO);

        service.validarAlcanceCierreVoz("1", "patient", turnos, summary());
        var response = service.finalizarEntrevistaVoz("1", "patient", turnos, summary());

        assertEquals("CIERRE_SIN_DATOS", response.origenRespuesta());
        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.NO_SOLICITADA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(24, chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        verify(ia, times(2)).consultarAlcance(anyString(), any());
        verify(ia, never()).consultar(anyString(), anyString(), eq(true));
        verify(ia, never()).consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class));
        verify(ia, never()).consultarCorreccionFinal(anyString(), anyString(), any());
        verifyNoInteractions(cola);
    }
    @Test void reintentoDeProcesamientoNoAgregaMensajeDelPacienteYNoReclasificaUnCompletado() {
        chat.agregarMensaje(new Mensaje("Tengo dolor de pecho", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.setFinalizado(true); chat.setEstadoClasificacion(EstadoClasificacionChat.FALLIDA);
        when(ia.consultar(anyString(), anyString(), eq(true))).thenReturn(result(4, true, List.of("dolor toracico"), List.of()));
        service.reintentarClasificacion("1", "patient");
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertThrows(ChatFinalizadoException.class, () -> service.reintentarClasificacion("1", "patient"));
        verify(cola, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), same(chat));
    }
    @Test void reintentoLegacyConDatosPendientesMantienePromptDeCierreYSinNuevaPregunta() throws Exception {
        chat.agregarMensaje(new Mensaje("¿Cuál es el principal motivo de consulta?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo fiebre", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(EstadoClasificacionChat.FALLIDA);
        var resultado = new TriageResultDTO("fiebre", List.of("fiebre"), "no informado", "no informado",
                null, List.of(), List.of(), List.of(), List.of(), "no informado", "no informado",
                2, false, "Consulta con un profesional de salud.");
        when(ia.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias. Ya registré tus respuestas.", resultado));

        service.reintentarClasificacion("1", "patient");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ia, times(1)).consultar(prompt.capture(), anyString(), eq(true));
        assertTrue(prompt.getValue().contains("Estado de cierre de entrevista: CIERRE_PREVIO"));
        assertTrue(prompt.getValue().contains("La entrevista ya está cerrada"));
        assertTrue(prompt.getValue().contains("no hagas nuevas preguntas"));
        assertFalse(prompt.getValue().contains("Pregunta desde cuándo"));
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE).count());
        var guardado = new ObjectMapper().readTree(chat.getResultadoTriageJson());
        assertEquals("CIERRE_PREVIO", guardado.path("seguimientoEntrevista").path("motivoCierre").asText());
        assertTrue(guardado.path("seguimientoEntrevista").path("datos").size() > 0);
        verify(ia, never()).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(cola, times(1)).finalizarTriageEIngresarACola(eq("patient"), any(), anyString(), same(chat));
    }
    @Test void unChatHistoricoFallidoNoPuedeReclasificarOtraConsulta() {
        chat.setFinalizado(true); chat.setEstadoClasificacion(EstadoClasificacionChat.FALLIDA);
        var nuevo = new Chat(chat.getPaciente()); nuevo.setId(2L);
        when(repo.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("patient")).thenReturn(Optional.of(nuevo));
        assertThrows(ChatFinalizadoException.class, () -> service.reintentarClasificacion("1", "patient"));
        verifyNoInteractions(ia, cola);
    }

    @Test void esquemaInvalidoEnClasificacionFinalTambienPideUnaCorreccionConElPlazoRestante() throws Exception {
        var mapper = new ObjectMapper();
        var raw = mock(GeminiJsonClient.class);
        var boundary = new TriageIaClient(raw, mapper);
        var flujo = new ChatService(repo, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
                mock(RepoEntradasCola.class), cola, boundary, mapper);
        var proposed = (tools.jackson.databind.node.ObjectNode)
                mapper.valueToTree(result(4, true, List.of("dolor toracico"), List.of()));
        var evidence = proposed.putArray("evidenciaClinica");
        evidence.addObject().put("campo", "sintomas").put("valor", "dolor de pecho")
                .put("cita", "Tengo dolor de pecho");
        evidence.addObject().put("campo", "signosAlarma").put("valor", "dolor toracico")
                .put("cita", "Tengo dolor de pecho");
        when(raw.generar(anyString(), anyString(), any(), any()))
                .thenReturn("{}", mapper.writeValueAsString(proposed), "{\"valido\":true}");
        flujo.enviarMensaje("1", "patient", "Tengo dolor de pecho");
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        verify(raw, never()).generar(anyString(), anyString(), any());
        verify(raw, times(3)).generar(anyString(), anyString(), any(), any());
        verify(cola, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), same(chat));
    }

    @Test void rechazoSemanticoRealGuiaUnaCorreccionConMotivoSeguroYPlazoRestante() throws Exception {
        prepararChatConInvitacionRespondida();
        var rechazo = rechazoSemanticoReal();
        doThrow(rechazo).when(ia).consultar(anyString(), anyString(), eq(true));
        when(ia.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenReturn(result(2, false, List.of(), List.of()));

        service.enviarMensaje("1", "patient", "No");

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertNotNull(chat.getResultadoTriageJson());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && m.getContenido().equals("No")).count());
        verify(ia, times(1)).consultarCorreccionFinal(
                contains("Una intensidad de otra sensacion no es dolor"), anyString(),
                argThat(plazo -> !plazo.isZero() && !plazo.isNegative() && plazo.compareTo(java.time.Duration.ofSeconds(45)) <= 0));
        verify(cola, times(1)).finalizarTriageEIngresarACola(eq("patient"), any(), anyString(), same(chat));
    }

    @Test void segundoRechazoSemanticoConservaEntrevistaCerradaFallidaYSinEfectosDeCola() throws Exception {
        prepararChatConInvitacionRespondida();
        var rechazo = rechazoSemanticoReal();
        doThrow(rechazo).when(ia).consultar(anyString(), anyString(), eq(true));
        doThrow(rechazo).when(ia).consultarCorreccionFinal(anyString(), anyString(), any());

        assertThrows(ProveedorIaException.class, () -> service.enviarMensaje("1", "patient", "No"));

        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.PACIENTE
                && m.getContenido().equals("No")).count());
        verify(ia, times(1)).consultarCorreccionFinal(
                contains("Una intensidad de otra sensacion no es dolor"), anyString(),
                argThat(plazo -> !plazo.isZero() && !plazo.isNegative() && plazo.compareTo(java.time.Duration.ofSeconds(45)) <= 0));
        verifyNoInteractions(cola);
    }

    private void prepararChatConInvitacionRespondida() {
        chat.setFinalizado(false);
        chat.setEstadoClasificacion(EstadoClasificacionChat.NO_SOLICITADA);
        chat.setResultadoTriageJson(null);
        chat.getMensajes().clear();
        for (var turno : List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "La extraño"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Siento un vacío en el pecho"),
                new TurnoVoz(AutorMensaje.BOT, "¿Sentís algo físico o es emocional?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Física"),
                new TurnoVoz(AutorMensaje.BOT, "¿Desde cuándo lo sentís?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Desde que me dejó"),
                new TurnoVoz(AutorMensaje.BOT, "Del 1 al 10, ¿qué intensidad tiene ese vacío físico?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "8"),
                new TurnoVoz(AutorMensaje.BOT, "¿Tenés algún otro síntoma además del vacío físico?"))) {
            chat.agregarMensaje(new Mensaje(turno.contenido(), turno.autor(),
                    turno.autor() == AutorMensaje.PACIENTE ? chat.getPaciente() : null));
        }
    }

    private ProveedorIaException rechazoSemanticoReal() throws Exception {
        var mapper = new ObjectMapper();
        var raw = mock(GeminiJsonClient.class);
        var boundary = new TriageIaClient(raw, mapper);
        var result = new TriageAiResponse(true, "Gracias", new TriageResultDTO(
                "vacío físico en el pecho", List.of("vacío físico en el pecho"), "desde ayer", "igual", 8,
                List.of(), List.of(), List.of(), List.of(), "no informado", "no informado",
                2, false, "Consulta con un profesional de salud."));
        var proposed = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(result);
        proposed.put("estadoEntrada", "RELEVANTE");
        proposed.putArray("evidenciaClinica").addObject()
                .put("campo", "sintomas").put("valor", "vacío físico en el pecho")
                .put("cita", "Siento un vacío físico en el pecho");
        when(raw.generar(anyString(), anyString(), any(), any()))
                .thenReturn(mapper.writeValueAsString(proposed), "{\"valido\":false,\"motivo\":\"INTENSIDAD_NO_DOLOR\"}");

        var input = mapper.createObjectNode();
        input.putArray("turnos").addObject().put("autor", "PACIENTE")
                .put("contenido", "Siento un vacío físico en el pecho");

        return assertThrows(ProveedorIaException.class,
                () -> boundary.consultar("Clasifica el caso", mapper.writeValueAsString(input), true));
    }

    @Test void falloDeTransporteNoSeConfundeConUnErrorDeEsquema() {
        var raw = mock(GeminiJsonClient.class);
        var boundary = new TriageIaClient(raw, new ObjectMapper());
        var flujo = new ChatService(repo, mock(RepoPacientes.class), mock(RepoConsultasMedicas.class),
                mock(RepoEntradasCola.class), cola, boundary, new ObjectMapper());
        when(raw.generar(anyString(), anyString(), any(), any())).thenThrow(new ProveedorIaException(new java.net.http.HttpTimeoutException("timeout")));
        assertThrows(ProveedorIaException.class, () -> flujo.enviarMensaje("1", "patient", "Tengo dolor de pecho"));
        assertTrue(chat.isFinalizado());
        assertEquals(EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        verify(raw, times(1)).generar(anyString(), anyString(), any(), any());
        verifyNoInteractions(cola);
    }
}
