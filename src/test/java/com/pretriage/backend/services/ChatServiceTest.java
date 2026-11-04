package com.pretriage.backend.services;

import com.pretriage.backend.services.voz.ValidadorCierreEntrevistaVoz;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.controllers.dtos.ResumenEntrevistaVoz;
import com.pretriage.backend.controllers.dtos.TurnoVoz;
import com.pretriage.backend.controllers.dtos.TriageAiResponse;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.AtencionPendienteException;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoPacientes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    @Test
    void cierreYaConfirmadoConservaAporteTardioYProcesaPendientesComoNoInformados() throws Exception {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Me duele la cabeza desde ayer, dolor 8/10"),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No, nada."),
                new TurnoVoz(AutorMensaje.PACIENTE, "Además tengo tos"));
        TriageResultDTO resultado = new TriageResultDTO("dolor de cabeza y tos", List.of("dolor de cabeza", "tos"),
                "desde ayer", "no informado", 8, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Inicio e impacto de la tos no informados", 2, false, "Solicitar evaluación médica");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias. Registramos tus respuestas.", resultado));

        chatService.finalizarEntrevistaVozConfirmadaParcial("1", "auth0|paciente", turnos);

        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertTrue(chat.getMensajes().stream().anyMatch(m -> m.getContenido().equals("Además tengo tos")));
        assertEquals("CIERRE_PREVIO", new ObjectMapper().readTree(chat.getResultadoTriageJson())
                .path("seguimientoEntrevista").path("motivoCierre").asText());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), same(chat));
    }

    private RepoChat repoChat;
    private RepoPacientes repoPacientes;
    private RepoConsultasMedicas repoConsultasMedicas;
    private RepoEntradasCola repoEntradasCola;
    private AtencionHospitalService atencionHospitalService;
    private ChatService chatService;
    private TriageIaClient triageIaClient;

    @BeforeEach
    void setUp() {
        repoChat = mock(RepoChat.class);
        when(repoChat.findForUpdate(any(), anyString())).thenAnswer(invocation ->
                repoChat.findByIdAndPacienteUsuarioAuthId(invocation.getArgument(0), invocation.getArgument(1)));
        repoPacientes = mock(RepoPacientes.class);
        repoConsultasMedicas = mock(RepoConsultasMedicas.class);
        repoEntradasCola = mock(RepoEntradasCola.class);
        atencionHospitalService = mock(AtencionHospitalService.class);
        triageIaClient = mock(TriageIaClient.class);
        when(triageIaClient.consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class)))
                .thenAnswer(invocation -> triageIaClient.consultar(invocation.getArgument(0),
                        invocation.getArgument(1), true));
        chatService = new ChatService(repoChat, repoPacientes, repoConsultasMedicas, repoEntradasCola, atencionHospitalService, triageIaClient, new ObjectMapper());
    }

    @Test
    void iniciarChatAsociaPacienteYAgregaSaludoInicial() {
        Paciente paciente = new Paciente();
        when(repoPacientes.findByUsuarioAuthId("auth0|paciente")).thenReturn(Optional.of(paciente));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatDTO resultado = chatService.iniciarChat("auth0|paciente");

        assertFalse(resultado.finalizado());
        assertEquals(1, resultado.mensajes().size());
        assertEquals(AutorMensaje.BOT.name(), resultado.mensajes().getFirst().autor());
        verify(repoChat).save(argThat(chat -> chat.getPaciente() == paciente));
    }


    @Test
    void iniciarChatSiempreCreaUnChatNuevoYCierraLosAbiertosPrevios() {
        Paciente paciente = pacienteConId(7L);
        Chat abiertoPrevio = new Chat(paciente);
        abiertoPrevio.setId(1L);
        Chat otroAbiertoPrevio = new Chat(paciente);
        otroAbiertoPrevio.setId(2L);
        when(repoPacientes.findByUsuarioAuthId("auth0|paciente")).thenReturn(Optional.of(paciente));
        when(repoConsultasMedicas.findByPacienteIdAndChatIsNotNull(7L)).thenReturn(List.of());
        when(repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse("auth0|paciente"))
                .thenReturn(List.of(abiertoPrevio, otroAbiertoPrevio));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatDTO resultado = chatService.iniciarChat("auth0|paciente");

        assertFalse(resultado.finalizado());
        assertTrue(abiertoPrevio.isFinalizado());
        assertTrue(otroAbiertoPrevio.isFinalizado());
        verify(repoChat).saveAll(List.of(abiertoPrevio, otroAbiertoPrevio));
        verify(repoChat).save(argThat(chat -> chat != abiertoPrevio && chat != otroAbiertoPrevio
                && !chat.isFinalizado() && chat.getPaciente() == paciente));
    }

    @Test
    void iniciarChatSeBloqueaSiHayAtencionPendienteConChatVinculado() {
        Paciente paciente = pacienteConId(7L);
        when(repoPacientes.findByUsuarioAuthId("auth0|paciente")).thenReturn(Optional.of(paciente));
        ConsultaMedica consulta = consultaConChatVinculado(paciente, 30L);
        when(repoConsultasMedicas.findByPacienteIdAndChatIsNotNull(7L)).thenReturn(List.of(consulta));
        when(repoEntradasCola.findByConsultaMedicaId(30L)).thenReturn(Optional.of(entradaEnEstado(EstadoEntradaCola.EN_COLA)));

        assertThrows(AtencionPendienteException.class, () -> chatService.iniciarChat("auth0|paciente"));

        verify(repoChat, never()).findAllByPacienteUsuarioAuthIdAndFinalizadoFalse(anyString());
        verify(repoChat, never()).saveAll(any());
        verify(repoChat, never()).save(any(Chat.class));
    }

    @Test
    void iniciarChatPermiteSiLaEntradaVinculadaEstaFinalizada() {
        assertIniciarChatPermitidoConEntradaEn(EstadoEntradaCola.FINALIZADA);
    }

    @Test
    void iniciarChatPermiteSiLaEntradaVinculadaEstaCancelada() {
        assertIniciarChatPermitidoConEntradaEn(EstadoEntradaCola.CANCELADA);
    }

    @Test
    void iniciarChatPermiteSinChatVinculadoAunqueHayaEntradaActiva() {
        Paciente paciente = pacienteConId(7L);
        when(repoPacientes.findByUsuarioAuthId("auth0|paciente")).thenReturn(Optional.of(paciente));
        when(repoConsultasMedicas.findByPacienteIdAndChatIsNotNull(7L)).thenReturn(List.of());
        when(repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse("auth0|paciente")).thenReturn(List.of());
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatDTO resultado = chatService.iniciarChat("auth0|paciente");

        assertFalse(resultado.finalizado());
        verify(repoEntradasCola, never()).findByConsultaMedicaId(any());
        verify(repoChat).save(any(Chat.class));
    }

    private void assertIniciarChatPermitidoConEntradaEn(EstadoEntradaCola estado) {
        Paciente paciente = pacienteConId(7L);
        when(repoPacientes.findByUsuarioAuthId("auth0|paciente")).thenReturn(Optional.of(paciente));
        ConsultaMedica consulta = consultaConChatVinculado(paciente, 30L);
        when(repoConsultasMedicas.findByPacienteIdAndChatIsNotNull(7L)).thenReturn(List.of(consulta));
        when(repoEntradasCola.findByConsultaMedicaId(30L)).thenReturn(Optional.of(entradaEnEstado(estado)));
        when(repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse("auth0|paciente")).thenReturn(List.of());
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatDTO resultado = chatService.iniciarChat("auth0|paciente");

        assertFalse(resultado.finalizado());
        verify(repoChat).save(any(Chat.class));
    }

    private static Paciente pacienteConId(Long id) {
        Paciente paciente = new Paciente();
        paciente.setId(id);
        return paciente;
    }

    private static ConsultaMedica consultaConChatVinculado(Paciente paciente, Long idConsulta) {
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setId(idConsulta);
        consulta.setPaciente(paciente);
        Chat chatVinculado = new Chat(paciente);
        chatVinculado.setFinalizado(true);
        consulta.setChat(chatVinculado);
        return consulta;
    }

    private static EntradaCola entradaEnEstado(EstadoEntradaCola estado) {
        EntradaCola entrada = new EntradaCola();
        entrada.setEstado(estado);
        return entrada;
    }

    @Test
    void sinFallback_enviarMensajeNoGeneraPreguntaLocalSiIaFalla() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("ollama down"));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.enviarMensaje("1", "auth0|paciente", "Tengo dolor de cabeza y fiebre desde ayer."));
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios, chat.getMensajes().size());
        verify(repoChat, never()).save(any());
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void motivoClaroRecibeUnaInvitacionAbiertaAntesDeClasificar() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Hay algo más que quieras contar sobre lo que te pasa?", null));

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "Tengo fiebre de 39 grados desde ayer y dolor de cabeza desde ayer, 8 de 10.");
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("¿Hay algo más que quieras contar sobre lo que te pasa?", respuesta.respuesta().contenido());
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void omiteBotQueCitaTurnoExcluidoPeroConservaBotTrasPacienteAdmitidoEnEntradaDelProveedor() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola", AutorMensaje.BOT, null));
        Mensaje excluido = new Mensaje("Siento un vacío en el pecho", AutorMensaje.PACIENTE, paciente);
        excluido.setExcluidoDelTriage(true);
        chat.agregarMensaje(excluido);
        String botQueCitaExcluido = "¿Qué querés decir con ‘siento un vacío en el pecho’?";
        chat.agregarMensaje(new Mensaje(botQueCitaExcluido, AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo tos", AutorMensaje.PACIENTE, paciente));
        String botTrasAdmitido = "¿Desde cuándo empezó la tos?";
        chat.agregarMensaje(new Mensaje(botTrasAdmitido, AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿La tos te dificulta hacer tus actividades habituales?", null));

        chatService.enviarMensaje("1", "auth0|paciente", "Desde ayer");

        org.mockito.ArgumentCaptor<String> datos = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(triageIaClient).consultarContinuacion(anyString(), datos.capture(), any());
        assertFalse(contieneTurnoJson(datos.getValue(), "PACIENTE", excluido.getContenido()));
        assertFalse(contieneTurnoJson(datos.getValue(), "BOT", botQueCitaExcluido));
        assertTrue(contieneTurnoJson(datos.getValue(), "PACIENTE", "Tengo tos"));
        assertTrue(contieneTurnoJson(datos.getValue(), "BOT", botTrasAdmitido));
        assertTrue(contieneTurnoJson(datos.getValue(), "PACIENTE", "Desde ayer"));
    }

    @Test
    void historialClinicoDeVozTampocoExponeBotQueCitaTurnoExcluido() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola", AutorMensaje.BOT, null));
        Mensaje excluido = new Mensaje("Siento un vacío en el pecho", AutorMensaje.PACIENTE, paciente);
        excluido.setExcluidoDelTriage(true);
        chat.agregarMensaje(excluido);
        String botQueCitaExcluido = "¿Qué querés decir con ‘siento un vacío en el pecho’?";
        chat.agregarMensaje(new Mensaje(botQueCitaExcluido, AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Me siento triste", AutorMensaje.PACIENTE, paciente));
        String botTrasAdmitido = "¿Querés contarme algo más?";
        chat.agregarMensaje(new Mensaje(botTrasAdmitido, AutorMensaje.BOT, null));
        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));

        var history = chatService.obtenerHistorialClinicoVoz("1", "auth0|paciente");

        assertFalse(history.stream().anyMatch(turno -> turno.contenido().equals(excluido.getContenido())));
        assertFalse(history.stream().anyMatch(turno -> turno.contenido().equals(botQueCitaExcluido)));
        assertTrue(history.stream().anyMatch(turno -> turno.contenido().equals("Me siento triste")));
        assertTrue(history.stream().anyMatch(turno -> turno.contenido().equals(botTrasAdmitido)));
    }
    @Test
    void cierraSinRecorrerChecklistCuandoElRelatoYaTieneContextoUtil() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo dolor de panza y mareo desde hace un rato.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Necesito precisar el dolor: del 0 al 10, cuanto te duele ahora y en que zona lo sentis?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("No se bien la intensidad; el dolor empezo suave ayer pero hoy me molesta mas. Es como abajo de la panza.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Tenes dificultad para respirar, dolor de pecho, confusion, desmayo, convulsiones o algun empeoramiento importante?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo un poco de nauseas desde esta mañana, no vomite.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Tenes antecedentes relevantes, alergias, tomas alguna medicacion o podria haber embarazo?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("El dolor viene y va, ahora sera un 6 de 10. Caminar me molesta un poco.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Antes de cerrar necesito ese dato: alguna enfermedad previa, alergia, medicacion habitual o posibilidad de embarazo?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias. Ya registré tus respuestas.", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "No tengo dolor de pecho ni me falta el aire. Me resulta difícil caminar por el mareo; me resulta difícil comer por las náuseas. No, no tengo más síntomas.");
        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class));
    }
    @Test
    void cierraConRelatoUtilAunqueNoSeHayanPreguntadoAntecedentesYAlarmas() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo dolor de panza y mareo desde hace un rato.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Necesito precisar el dolor: del 0 al 10, cuanto te duele ahora y en que zona lo sentis?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("No se bien la intensidad; el dolor empezo suave ayer pero hoy me molesta mas. Es como abajo de la panza.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Tenes dificultad para respirar, dolor de pecho, confusion, desmayo, convulsiones o algun empeoramiento importante?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo un poco de nauseas desde esta mañana, no vomite.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Tenes antecedentes relevantes, alergias, tomas alguna medicacion o podria haber embarazo?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias. Ya registré tus respuestas.", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "El dolor empezó ayer, viene y va, ahora será un 6 de 10. Caminar me molesta un poco por el mareo. Me resulta difícil comer por las náuseas. No, no tengo más síntomas.");
        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class));
    }
    @Test
    void sinFallback_enviarMensajeNoMarcaAlarmasNegadasComoUrgentes() {
        Paciente paciente = new Paciente();
        paciente.setId(10L);
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo fiebre de 39 grados desde ayer y dolor fuerte de cabeza.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Necesito precisar el dolor: del 0 al 10, cuanto te duele ahora y en que zona lo sentis?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Empezo ayer a la tarde. La fiebre llego a 39 y no baja mucho con paracetamol.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("Tenes dificultad para respirar, dolor de pecho, confusion, desmayo, convulsiones o algun empeoramiento importante?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("ollama down"));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.enviarMensaje("1", "auth0|paciente",
                "El dolor de cabeza empezó ayer a la tarde y es 7 de 10, en la frente. La fiebre empezó ayer y llegó a 39 grados. No tengo dificultad para respirar, dolor de pecho, confusion, desmayos ni convulsiones, enfermedades previas, alergias ni medicacion habitual. No, no tengo más síntomas."));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 1, chat.getMensajes().size());
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void corrigePrioridadCuandoLaAlarmaFinalValidadaNoCoincideConLaFraseLiteral() {
        Chat chat = prepararChat();
        var textoPaciente = List.of(new TurnoVoz(AutorMensaje.PACIENTE,
                "No reconozco dónde estoy desde hace una hora. Quiero terminar."));
        assertFalse(ValidadorCierreEntrevistaVoz.contieneAlarmaLiteral(textoPaciente));
        assertTrue(ValidadorCierreEntrevistaVoz.signosAlarmaAfirmados(List.of("confusion")).contains("confusion"));
        TriageResultDTO clasificacionInicial = new TriageResultDTO("desorientacion", List.of("desorientacion"),
                "hace una hora", "no informado", null, List.of("confusion"), List.of(), List.of(), List.of(),
                "no informado", "no informado", 3, false, "Consulte presencialmente.");
        TriageResultDTO clasificacionCorregida = new TriageResultDTO("desorientacion", List.of("desorientacion"),
                "hace una hora", "no informado", null, List.of("confusion"), List.of(), List.of(), List.of(),
                "no informado", "no informado", 4, true, "Busque atencion urgente.");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Cierre inicial", clasificacionInicial));
        when(triageIaClient.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(true, "Cierre corregido", clasificacionCorregida));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var response = chatService.enviarMensaje("1", "auth0|paciente", textoPaciente.getFirst().contenido());

        assertTrue(chat.isFinalizado());
        TriageResultDTO resultadoGuardado = new ObjectMapper().readValue(
                chat.getResultadoTriageJson(), TriageResultDTO.class);
        assertEquals(4, resultadoGuardado.nivelPrioridad());
        assertTrue(resultadoGuardado.requiereAtencionInmediata());
        assertEquals("Cierre corregido Busque atencion urgente.", response.respuesta().contenido());
        verify(triageIaClient).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(),
                eq(NivelDeGravedad.MUY_URGENTE), anyString(), eq(chat));
    }

    @Test
    void corrigeUnaVezSintomasAusentesYValidaUnHechoEmocionalExpreso() {
        Chat chat = chatDeVoz(new Paciente());
        String cita = "Me siento muy triste y con angustia desde ayer.";
        List<TurnoVoz> turnos = entrevistaConCierreSolicitado(cita);
        GeminiJsonClient gemini = mock(GeminiJsonClient.class);
        TriageIaClient validador = new TriageIaClient(gemini, new ObjectMapper());
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(providerFinalSinSintomas(), providerFinalEmocional(cita), "{\"valido\":true}");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenAnswer(invocation -> validador.consultar(invocation.getArgument(0), invocation.getArgument(1), true));
        when(triageIaClient.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenAnswer(invocation -> validador.consultarCorreccionFinal(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var resultado = chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos, resumenEmocional());

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertTrue(chat.getResultadoTriageJson().contains("\"sintomas\":[\"tristeza y angustia\"]"));
        assertEquals(mensajesPrevios + turnos.size() + 1, chat.getMensajes().size());
        assertEquals("Cierre emocional validado.", resultado.respuesta().contenido());
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<java.time.Duration> plazo =
                org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(triageIaClient).consultarCorreccionFinal(prompt.capture(), anyString(), plazo.capture());
        assertTrue(prompt.getValue().contains("también si describe malestar emocional o de salud mental"));
        assertFalse(prompt.getValue().contains("missing_or_uninformative"));
        assertFalse(prompt.getValue().contains(cita));
        assertTrue(plazo.getValue().isPositive());
        assertFalse(plazo.getValue().compareTo(java.time.Duration.ofSeconds(45)) > 0);
        verify(triageIaClient, times(1)).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(atencionHospitalService, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), eq(chat));
    }

    @Test
    void reintentoManualUsaElMismoFeedbackDeSintomasAusentes() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(9L);
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA);
        String cita = "Me siento muy triste y con angustia desde ayer.";
        chat.agregarMensaje(new Mensaje(cita, AutorMensaje.PACIENTE, paciente));
        when(repoChat.findForUpdate(9L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth0|paciente"))
                .thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        GeminiJsonClient gemini = mock(GeminiJsonClient.class);
        TriageIaClient validador = new TriageIaClient(gemini, new ObjectMapper());
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(providerFinalSinSintomas(), providerFinalEmocional(cita), "{\"valido\":true}");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenAnswer(invocation -> validador.consultar(invocation.getArgument(0), invocation.getArgument(1), true));
        when(triageIaClient.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenAnswer(invocation -> validador.consultarCorreccionFinal(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var resultado = chatService.reintentarClasificacion("9", "auth0|paciente");

        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertEquals(mensajesPrevios + 1, chat.getMensajes().size());
        assertEquals("Cierre emocional validado.", resultado.respuesta().contenido());
        verify(triageIaClient, times(1)).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(atencionHospitalService, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), eq(chat));
    }

    @Test
    void cierrePorTextoUsaFeedbackDeSintomasAusentesSinReabrirLaEntrevista() {
        Chat chat = prepararChat();
        String cita = "Me siento muy triste y con angustia desde ayer. No quiero seguir respondiendo.";
        GeminiJsonClient gemini = mock(GeminiJsonClient.class);
        TriageIaClient validador = new TriageIaClient(gemini, new ObjectMapper());
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(providerFinalSinSintomas(), providerFinalEmocional(cita), "{\"valido\":true}");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenAnswer(invocation -> validador.consultar(invocation.getArgument(0), invocation.getArgument(1), true));
        when(triageIaClient.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenAnswer(invocation -> validador.consultarCorreccionFinal(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var resultado = chatService.enviarMensaje("1", "auth0|paciente", cita);

        assertTrue(chat.isFinalizado());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("Cierre emocional validado.", resultado.respuesta().contenido());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(triageIaClient, times(1)).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(atencionHospitalService, times(1)).finalizarTriageEIngresarACola(anyString(), any(), anyString(), eq(chat));
    }

    @Test
    void correccionDeSintomasQueSigueVaciaDejaClasificacionFallidaSinDuplicarNiEncolar() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = entrevistaConCierreSolicitado("Me siento muy triste y con angustia desde ayer.");
        GeminiJsonClient gemini = mock(GeminiJsonClient.class);
        TriageIaClient validador = new TriageIaClient(gemini, new ObjectMapper());
        when(gemini.generar(anyString(), anyString(), any(), any()))
                .thenReturn(providerFinalSinSintomas(), providerFinalSinSintomas());
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenAnswer(invocation -> validador.consultar(invocation.getArgument(0), invocation.getArgument(1), true));
        when(triageIaClient.consultarCorreccionFinal(anyString(), anyString(), any()))
                .thenAnswer(invocation -> validador.consultarCorreccionFinal(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz(
                "1", "auth0|paciente", turnos, resumenEmocional()));

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + turnos.size(), chat.getMensajes().size());
        verify(triageIaClient, times(1)).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(atencionHospitalService, never()).finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class));
    }

    private String providerFinalSinSintomas() {
        return providerFinal("[]", "[]", "malestar emocional", "Cierre vacío");
    }

    private String providerFinalEmocional(String cita) {
        return providerFinal("[\"tristeza y angustia\"]",
                "[{\"campo\":\"sintomas\",\"valor\":\"tristeza y angustia\",\"cita\":\"" + cita + "\"}]",
                "tristeza y angustia", "Cierre emocional validado.");
    }

    private String providerFinal(String sintomas, String evidencia, String motivo, String mensaje) {
        return """
                {"finalizado":true,"mensaje":"%s","estadoEntrada":"RELEVANTE","evidenciaClinica":%s,
                 "resultado":{"motivoConsulta":"%s","sintomas":%s,"inicio":"desde ayer","evolucion":"no informado",
                 "intensidadDolor":null,"signosAlarma":[],"antecedentesRelevantes":[],"medicamentos":[],"alergias":[],
                 "posibilidadEmbarazo":"no informado","observaciones":"no informado","nivelPrioridad":2,
                 "requiereAtencionInmediata":false,"recomendacionSeguridad":"Consulte presencialmente."}}
                """.formatted(mensaje, evidencia, motivo, sintomas);
    }
    @Test
    void corrigeUnaPreguntaRepetidaUnaVezConGeminiYConservaLaEntrevistaAbierta() {
        Paciente paciente = new Paciente();
        paciente.setId(10L);
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Voy a hacerte algunas preguntas breves para registrar tus sintomas.", AutorMensaje.BOT, null));
        String preguntaAnterior = "¿Qué sentís exactamente?";
        chat.agregarMensaje(new Mensaje("Me siento mal.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje(preguntaAnterior, AutorMensaje.BOT, null));

        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, preguntaAnterior, null))
                .thenReturn(new TriageAiResponse(false, "¿Podés describirme ese malestar?", null));

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No sé bien, es algo raro.");
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("¿Podés describirme ese malestar?", respuesta.respuesta().contenido());
        verify(triageIaClient, times(2)).consultarContinuacion(anyString(), anyString(), any());
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_respuestaNoFinalValidaQueRepitePreguntaPideOtroDatoSinCerrar() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(2L);
        String pregunta = "¿Cual es el sintoma principal?";
        chat.agregarMensaje(new Mensaje("Inicio", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("No me siento bien desde ayer.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje(pregunta, AutorMensaje.BOT, null));
        when(repoChat.findByIdAndPacienteUsuarioAuthId(2L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, pregunta, null))
                .thenReturn(new TriageAiResponse(false, "¿Cómo describirías la molestia?", null));

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("2", "auth0|paciente", "No sé bien.");
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("¿Cómo describirías la molestia?", respuesta.respuesta().contenido());
        verify(triageIaClient, times(2)).consultarContinuacion(anyString(), anyString(), any());
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void conservaResultadoFinalValidoDeIaYRegistraSuOrigen() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Inicio", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Tengo dolor de garganta desde ayer.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("¿Como evoluciono?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("Empeoro un poco, intensidad 5 de 10.", AutorMensaje.PACIENTE, paciente));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));

        TriageResultDTO resultadoIa = new TriageResultDTO(
                "dolor de garganta", List.of("dolor de garganta"), "desde ayer", "empeorando", 5,
                List.of(), List.of(), List.of(), List.of(), "no aplica",
                "evaluacion completa", 3, false, "Acudi a una guardia si empeora.");
        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenReturn(new TriageAiResponse(true, "Cierre IA detallado", resultadoIa));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "No tengo fiebre ni dificultad para respirar. No tengo enfermedades previas, alergias ni medicacion. No, no tengo más síntomas.");

        assertTrue(chat.isFinalizado());
        assertEquals("Cierre IA detallado", respuesta.respuesta().contenido());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        assertTrue(chat.getResultadoTriageJson().contains("\"origenClasificacion\":\"GEMINI\""));
        assertEquals(resultadoIa, new ObjectMapper().readValue(chat.getResultadoTriageJson(), TriageResultDTO.class));
        verify(triageIaClient).consultar(anyString(), argThat(datos -> contieneTurnoJson(datos,
                "PACIENTE", "Tengo dolor de garganta desde ayer.")), eq(true));
    }

    @Test
    void sinFallback_unTurnoSinInformacionNoSeClasificaComoNoUrgente() {
        Paciente paciente = new Paciente();
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Inicio", AutorMensaje.BOT, null));
        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Qué sentís exactamente?", null));

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No se.");
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("¿Qué sentís exactamente?", respuesta.respuesta().contenido());
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void aceptaAlarmaReconocidaPorIaDesdeElPrimerTurno() {
        Chat chat = prepararChat();
        TriageResultDTO resultado = resultadoFinal(4, true);
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenReturn(new TriageAiResponse(true, "Busque atencion urgente.", resultado));

        var turno = chatService.enviarMensaje("1", "auth0|paciente", "No puedo respirar.");

        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", turno.origenRespuesta());
        assertEquals(resultado, new ObjectMapper().readValue(chat.getResultadoTriageJson(), TriageResultDTO.class));
        verify(atencionHospitalService).finalizarTriageEIngresarACola(eq("auth0|paciente"), eq(NivelDeGravedad.MUY_URGENTE), anyString(), same(chat));
    }

    @Test
    void cierraConMotivoIntensidadYConfirmacionDeEvolucionSinChecklist() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Tengo dolor de garganta desde ayer.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("Es 5 de 10.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenReturn(new TriageAiResponse(true, "Listo.", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "Sigue igual.");
        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class));
    }

    @Test
    void limiteSinAvanceNoGeneraClasificacionLocalSiGeminiFalla() {
        Chat chat = prepararChat();
        for (int i = 1; i < com.pretriage.backend.services.entrevista.ProgresoEntrevista.MAX_SIN_AVANCE; i++) {
            chat.agregarMensaje(new Mensaje("No se.", AutorMensaje.PACIENTE, chat.getPaciente()));
        }
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("unavailable"));
        assertThrows(ProveedorIaException.class, () -> chatService.enviarMensaje("1", "auth0|paciente", "No se."));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(com.pretriage.backend.services.entrevista.ProgresoEntrevista.MAX_SIN_AVANCE + 1,
                chat.getMensajes().size());
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_alarmaLocalIncluyeAvisoUrgenteYResultadoCoherenteSiGeminiFalla() {
        Chat chat = prepararChat();
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean())).thenThrow(new RuntimeException("unavailable"));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.enviarMensaje("1", "auth0|paciente", "Tengo confusion desde hace una hora."));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 1, chat.getMensajes().size());
        verify(repoChat).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void cierreIaInmediatoMuestraLaRecomendacionAunqueFalteEnElMensaje() {
        Chat chat = prepararChat();
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean())).thenReturn(new TriageAiResponse(
                true,
                "Gracias. Registre tus sintomas y la preclasificacion.",
                new TriageResultDTO("dificultad respiratoria", List.of("dificultad respiratoria"), "ahora",
                        "empeorando", null, List.of("dificultad respiratoria"), List.of(), List.of(), List.of(),
                        "no informado", "signo de alarma", 5, true,
                        "Llama a emergencias de inmediato.")));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No puedo respirar.");

        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        assertTrue(respuesta.respuesta().contenido().contains("Llama a emergencias de inmediato."));
        assertEquals(1, respuesta.respuesta().contenido().split("Llama a emergencias de inmediato.", -1).length - 1);
    }

    private Chat prepararChat() {
        Chat chat = new Chat(new Paciente());
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Cual es el motivo de consulta?", AutorMensaje.BOT, null));
        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return chat;
    }

    private TriageResultDTO resultadoFinal(int prioridad, boolean inmediata) {
        return new TriageResultDTO("Sintoma referido", List.of("sintoma referido"), "hoy", "sin cambios", null,
                List.of(), List.of(), List.of(), List.of(), "no informado", "no informado", prioridad,
                inmediata, "Busque atencion presencial.");
    }

    @Test
    void entrevistaDeVozGuardaTranscripcionYConservaClasificacionDeGemini() {
        Paciente paciente = new Paciente();
        Chat chat = chatDeVoz(paciente);
        TriageResultDTO resultadoIa = new TriageResultDTO("dolor de cabeza", List.of("dolor de cabeza", "fiebre"),
                "desde ayer", "se mantiene", 6, List.of(), List.of(), List.of(), List.of(), "no",
                "Niega dificultad respiratoria", 3, false, "Si empeora, consulte a una guardia.");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias. Registre tus sintomas y la preclasificacion.", resultadoIa));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var resultado = chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnosDeVoz(), resumen(List.of()));

        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", resultado.origenRespuesta());
        assertEquals(List.of("BOT", "PACIENTE", "BOT", "PACIENTE", "BOT", "PACIENTE", "BOT", "PACIENTE", "BOT"),
                chat.getMensajes().stream().map(mensaje -> mensaje.getAutor().name()).toList());
        assertTrue(chat.getResultadoTriageJson().contains("\"origenClasificacion\":\"GEMINI\""));
        verify(triageIaClient).consultar(anyString(), argThat(datos -> contieneTurnoJson(datos, "PACIENTE",
                "Me duele la cabeza desde ayer y tengo fiebre de 39 grados desde ayer")
                && contieneResumenJson(datos, "motivoConsulta", "dolor de cabeza con fiebre")), eq(true));
        verify(atencionHospitalService).finalizarTriageEIngresarACola(eq("auth0|paciente"), eq(NivelDeGravedad.URGENTE), anyString(), any(Chat.class));
    }

    @Test
    void vozSinHechosTrasCuatroAclaracionesCierraSinClasificarNiEncolar() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = entrevistaVozSinHechos();
        var progreso = progresoVoz(turnos);
        assertEquals(com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.SIN_AVANCE,
                progreso.motivoCierre());
        assertFalse(progreso.tieneHechosClinicos());
        // The scope probe is non-relevant; it must close administratively before classification.
        when(triageIaClient.consultarAlcance(anyString(), any(java.time.Duration.class)))
                .thenReturn(com.pretriage.backend.controllers.dtos.EstadoEntradaChat.FUERA_DE_CONTEXTO);

        int mensajesAntes = chat.getMensajes().size();
        var respuesta = chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos,
                resumenSinHechos());

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.NO_SOLICITADA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals("CIERRE_SIN_DATOS", respuesta.origenRespuesta());
        assertEquals(mensajesAntes + turnos.size() + 1, chat.getMensajes().size());
        for (TurnoVoz turno : turnos) {
            assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == turno.autor()
                    && turno.contenido().equals(m.getContenido())).count(),
                    "Cada turno literal se conserva una sola vez: " + turno.contenido());
        }
        assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == AutorMensaje.BOT
                && respuesta.respuesta().contenido().equals(m.getContenido())).count());
        verify(triageIaClient).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class));
        verify(triageIaClient, never()).consultarCorreccionFinal(anyString(), anyString(), any());
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void reintentoDeVozFallidaSinHechosCierraSinDuplicarLaTranscripcion() {
        Chat chat = chatDeVoz(new Paciente());
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA);
        List<TurnoVoz> transcripcion = entrevistaVozSinHechos();
        transcripcion.forEach(turno -> chat.agregarMensaje(
                new Mensaje(turno.contenido(), turno.autor(), chat.getPaciente())));
        when(repoChat.findForUpdate(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth0|paciente"))
                .thenReturn(Optional.of(chat));
        when(triageIaClient.consultarAlcance(anyString(), any(java.time.Duration.class)))
                .thenReturn(com.pretriage.backend.controllers.dtos.EstadoEntradaChat.AMBIGUO);

        int mensajesAntes = chat.getMensajes().size();
        var respuesta = chatService.reintentarClasificacion("1", "auth0|paciente");

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.NO_SOLICITADA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals("CIERRE_SIN_DATOS", respuesta.origenRespuesta());
        assertEquals(mensajesAntes + 1, chat.getMensajes().size());
        for (TurnoVoz turno : transcripcion) {
            assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == turno.autor()
                    && turno.contenido().equals(m.getContenido())).count(),
                    "El reintento no duplica el turno: " + turno.contenido());
        }
        verify(triageIaClient).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class));
        verifyNoInteractions(atencionHospitalService);
        verify(repoChat).save(chat);
    }

    @Test
    void motivoColoquialYEmocionalNoSeConvierteEnCierreSinDatosSiGeminiFalla() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE,
                        "Estoy atravesando una angustia enorme y cuando me levanto se me va el mundo."),
                new TurnoVoz(AutorMensaje.BOT, "¿Desde cuándo te sentís así?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Desde esta mañana."),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué cambio notaste?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Me preocupa mucho."),
                new TurnoVoz(AutorMensaje.BOT, "¿Querés seguir contando algo más?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No quiero seguir respondiendo."));
        var progreso = progresoVoz(turnos);
        assertEquals(com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.SOLICITUD_PACIENTE,
                progreso.motivoCierre());
        assertTrue(progreso.tieneHechosClinicos());
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenThrow(new ProveedorIaException(new RuntimeException("provider unavailable")));

        int mensajesAntes = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz(
                "1", "auth0|paciente", turnos, resumenEmocional()));

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesAntes + turnos.size(), chat.getMensajes().size());
        for (TurnoVoz turno : turnos) {
            assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == turno.autor()
                    && turno.contenido().equals(m.getContenido())).count(),
                    "Se conserva la respuesta literal ante fallo del proveedor: " + turno.contenido());
        }
        verify(repoChat, atLeastOnce()).save(chat);
        verify(triageIaClient, never()).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true), argThat(plazo -> !plazo.isNegative() && !plazo.isZero() && plazo.compareTo(java.time.Duration.ofSeconds(45)) <= 0));
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void respuestaFueraDeAlcanceNoSeSanitizaComoCierreSinDatosSiHayUnMotivoClinico() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Me duele la cabeza desde ayer."),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué intensidad tiene el dolor?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Todavía no sé decirte."),
                new TurnoVoz(AutorMensaje.BOT, "¿Querés agregar algo más antes de cerrar?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No quiero seguir respondiendo."));
        var progreso = progresoVoz(turnos);
        assertEquals(com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.SOLICITUD_PACIENTE,
                progreso.motivoCierre());
        assertTrue(progreso.tieneHechosClinicos());
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(
                true, "No se pudo clasificar.", resultadoFinal(3, false),
                com.pretriage.backend.controllers.dtos.EstadoEntradaChat.FUERA_DE_CONTEXTO));

        int mensajesAntes = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz(
                "1", "auth0|paciente", turnos, new ResumenEntrevistaVoz("dolor de cabeza",
                        List.of("dolor de cabeza"), "desde ayer", "no informado", null,
                        List.of(), List.of(), List.of(), List.of(), "no informado", "dolor de cabeza desde ayer")));

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesAntes + turnos.size(), chat.getMensajes().size());
        assertTrue(chat.getMensajes().stream().anyMatch(m -> m.getAutor() == AutorMensaje.PACIENTE
                && "Me duele la cabeza desde ayer.".equals(m.getContenido())));
        assertFalse(chat.getMensajes().stream().anyMatch(m -> "No se pudo clasificar.".equals(m.getContenido())));
        verify(repoChat, atLeastOnce()).save(chat);
        verify(triageIaClient, never()).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true), argThat(plazo -> !plazo.isNegative() && !plazo.isZero() && plazo.compareTo(java.time.Duration.ofSeconds(45)) <= 0));
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void motivoColoquialSinHechosLocalesAlcanceRelevanteYFinalFueraDeContextoQuedaFallido() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = entrevistaVozColoquialSinHechos();
        var progreso = progresoVoz(turnos);
        assertEquals(com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.SIN_AVANCE,
                progreso.motivoCierre());
        assertFalse(progreso.tieneHechosClinicos());
        when(triageIaClient.consultarAlcance(anyString(), any(java.time.Duration.class)))
                .thenReturn(com.pretriage.backend.controllers.dtos.EstadoEntradaChat.RELEVANTE);
        when(triageIaClient.consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class)))
                .thenReturn(new TriageAiResponse(true, "No se pudo clasificar.", resultadoFinal(3, false),
                        com.pretriage.backend.controllers.dtos.EstadoEntradaChat.FUERA_DE_CONTEXTO));

        int mensajesAntes = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz(
                "1", "auth0|paciente", turnos, resumenSinHechos()));

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesAntes + turnos.size(), chat.getMensajes().size());
        assertFalse(chat.getMensajes().stream().anyMatch(m -> m.getContenido().equals("No se pudo clasificar.")));
        for (TurnoVoz turno : turnos) {
            assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == turno.autor()
                    && turno.contenido().equals(m.getContenido())).count());
        }
        verify(triageIaClient).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
        var alcancePlazo = org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        var finalPlazo = org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(triageIaClient).consultarAlcance(anyString(), alcancePlazo.capture());
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true), finalPlazo.capture());
        assertTrue(alcancePlazo.getValue().isPositive());
        assertTrue(finalPlazo.getValue().isPositive());
        assertFalse(finalPlazo.getValue().compareTo(alcancePlazo.getValue()) > 0,
                "El presupuesto de clasificación no debe reiniciarse después del probe de alcance.");
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void falloDelProbeDeAlcanceNoCierraFalsamenteUnaEntrevistaSinDatos() {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = entrevistaVozSinHechos();
        when(triageIaClient.consultarAlcance(anyString(), any(java.time.Duration.class)))
                .thenThrow(new ProveedorIaException(new java.net.http.HttpTimeoutException("scope timeout")));

        int mensajesAntes = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz(
                "1", "auth0|paciente", turnos, resumenSinHechos()));

        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA,
                chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertEquals(mensajesAntes + turnos.size(), chat.getMensajes().size());
        for (TurnoVoz turno : turnos) {
            assertEquals(1, chat.getMensajes().stream().filter(m -> m.getAutor() == turno.autor()
                    && turno.contenido().equals(m.getContenido())).count());
        }
        verify(triageIaClient).consultarAlcance(anyString(), any(java.time.Duration.class));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true), any(java.time.Duration.class));
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_entrevistaDeVozSinGeminiUsaElResumenYRespetaAlarmasDelEntrevistador() {
        Paciente paciente = new Paciente();
        Chat chat = chatDeVoz(paciente);
        when(triageIaClient.consultar(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("ollama down"));
        when(atencionHospitalService.finalizarTriageEIngresarACola(eq("auth0|paciente"), any(NivelDeGravedad.class), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnosDeVoz(),
                resumen(List.of("rigidez de nuca"))));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_entrevistaDeVozRechazaClasificacionSinUrgenciaSiHayAlarma() {
        Paciente paciente = new Paciente();
        Chat chat = chatDeVoz(paciente);
        TriageResultDTO resultadoIa = new TriageResultDTO("dolor de cabeza", List.of("dolor de cabeza"),
                "desde ayer", "se mantiene", 6, List.of(), List.of(), List.of(), List.of(), "no",
                "sin datos", 2, false, "Consulte si empeora.");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Gracias.", resultadoIa));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo confusion")),
                resumen(List.of("confusion"))));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void registrarTurnosVozGuardaSinFinalizar() {
        Paciente paciente = new Paciente();
        Chat chat = chatDeVoz(paciente);

        chatService.registrarTurnosVoz("1", "auth0|paciente", turnosDeVoz());

        assertFalse(chat.isFinalizado());
        assertEquals(8, chat.getMensajes().size());
        verify(repoChat).save(chat);
        verifyNoInteractions(triageIaClient, atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozPriorizaDolorEInicioLiteralesSobreResumenContradictorio() {
        Chat chat = chatDeVoz(new Paciente());
        when(triageIaClient.consultar(anyString(), anyString(), eq(true))).thenThrow(new RuntimeException("offline"));
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("dolor leve", List.of("dolor de cabeza"),
                "hoy", "mejorando", 2, List.of(), List.of(), List.of(), List.of(), "no informado", "dolor leve");

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                entrevistaConCierreSolicitado("Tengo dolor de cabeza y fiebre desde ayer. Dolor 8/10. Empeoro."), resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozConservaCeroExplicitoYNoSustituyeDatosPorNoInformado() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("tos", List.of("no informado"),
                "no informado", "no informado", 8, List.of(), List.of(), List.of(), List.of(), "no informado", "no informado");

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                entrevistaConCierreSolicitado("Tengo tos desde ayer, sigue igual. Tengo dolor de cabeza desde ayer, dolor 0/10."), resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozUsaUltimaIntensidadExplicitaDeUnaCorreccion() {
        Chat chat = chatDeVoz(new Paciente());
        chat.agregarMensaje(new Mensaje("Tenia dolor 2/10 desde ayer.", AutorMensaje.PACIENTE, chat.getPaciente()));
        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                entrevistaConCierreSolicitado("Tengo dolor de cabeza. Ahora es 8/10."), resumen(List.of())));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozConservaDolorSobreDiezAnteResumenContradictorio() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("dolor leve", List.of("dolor de cabeza"),
                "desde ayer", "igual", 2, List.of(), List.of(), List.of(), List.of(), "no informado", "dolor leve");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true))).thenThrow(new RuntimeException("offline"));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                entrevistaConCierreSolicitado("Tengo dolor de cabeza desde ayer, dolor 8 sobre 10"), resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozConservaRespuestaNumericaALaPreguntaDeDolor() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("dolor leve", List.of("dolor de cabeza"),
                "desde ayer", "igual", 2, List.of(), List.of(), List.of(), List.of(), "no informado", "dolor leve");
        var turnos = List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de cabeza desde ayer"),
                new TurnoVoz(AutorMensaje.BOT, "Del 0 al 10, cuanto te duele?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "8"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No tengo dificultad para respirar ni dolor de pecho"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No tengo antecedentes ni alergias y no tomo medicamentos"),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar sobre lo que te pasa?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No, no tengo más síntomas."));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true))).thenThrow(new RuntimeException("offline"));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos, resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void cierreVozPorDisneaUOpresionNoAceptaClasificacionSinUrgencia() {
        for (String sintoma : List.of("disnea", "opresion toracica")) {
            Chat chat = chatDeVoz(new Paciente());
            TriageResultDTO noUrgente = new TriageResultDTO(sintoma, List.of(sintoma), "hoy", "igual", null,
                    List.of(), List.of(), List.of(), List.of(), "no informado", "no informado", 2, false,
                    "Evaluacion presencial");
            when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                    .thenReturn(new TriageAiResponse(true, "Gracias", noUrgente));

            assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                    List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Tengo " + sintoma)), resumen(List.of())));
            assertTrue(chat.isFinalizado(), sintoma);
            assertNull(chat.getResultadoTriageJson());
            assertEquals(2, chat.getMensajes().size());
            verifyNoInteractions(atencionHospitalService);
        }
    }

    @Test
    void sinFallback_fallbackVozCompletaCamposNoExtraiblesSinPerderSintomasLiterales() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("cefalea", List.of("cefalea", "fotofobia"),
                "al amanecer", "se mantiene", 4, List.of(), List.of(), List.of(), List.of(), "no informado", "sin datos");
        List<TurnoVoz> turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de cabeza desde el amanecer. La fotofobia empezó al amanecer."),
                new TurnoVoz(AutorMensaje.BOT, "Cuanto te duele del 0 al 10?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "4/10. No tengo dificultad para respirar ni dolor de pecho."),
                new TurnoVoz(AutorMensaje.PACIENTE, "Me resulta difícil hacer mis tareas por la fotofobia."),
                new TurnoVoz(AutorMensaje.PACIENTE, "No tengo enfermedades previas, alergias ni medicacion habitual."),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar sobre lo que te pasa?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No, no tengo más síntomas."));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos, resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozRespetaNegacionesExplicitasDeSintomasYContexto() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("tos y fiebre", List.of("tos", "fiebre"),
                "ayer", "igual", 2, List.of(), List.of("asma"), List.of("ibuprofeno"),
                List.of("penicilina"), "si", "fiebre y embarazo");
        var turnos = new java.util.ArrayList<>(entrevistaConCierreSolicitado("Tengo tos desde ayer. No tengo fiebre. Tengo dolor de cabeza desde ayer, dolor 0/10."));
        turnos.add(new TurnoVoz(AutorMensaje.PACIENTE, "No estoy embarazada."));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos, resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozNoPierdeAlarmaLiteralTrasUnaNegacionNoRelacionada() {
        Chat chat = chatDeVoz(new Paciente());
        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", List.of(new TurnoVoz(AutorMensaje.PACIENTE,
                "No tengo alergias. Tengo dificultad para respirar.")), resumen(List.of())));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozNoTransformaEtiquetasDeAlarmaNegadasEnUrgencia() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("tos", List.of("tos"), "ayer", "igual", 0,
                List.of("ninguno", "sin signos de alarma", "signos negados"),
                List.of(), List.of(), List.of(), "no informado", "sin alarmas");

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                entrevistaConCierreSolicitado("Tengo tos desde ayer, sigue igual. Tengo dolor de cabeza desde ayer, dolor 0/10."), resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void sinFallback_fallbackVozConservaCorreccionLiteralPosteriorDeUnaAlergiaNegada() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz resumen = new ResumenEntrevistaVoz("tos", List.of("tos"), "ayer", "igual", 0,
                List.of(), List.of(), List.of(), List.of("penicilina"), "no informado", "alergia corregida");
        var turnos = new java.util.ArrayList<>(entrevistaConCierreSolicitado("Tengo tos desde ayer, igual. Tengo dolor de cabeza desde ayer, dolor 0/10."));
        turnos.add(new TurnoVoz(AutorMensaje.PACIENTE, "Me corrijo: soy alergica a penicilina."));

        int mensajesPrevios = chat.getMensajes().size();
        assertThrows(ProveedorIaException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos, resumen));
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.FALLIDA, chat.getEstadoClasificacion());
        assertNull(chat.getResultadoTriageJson());
        assertTrue(chat.getMensajes().size() > mensajesPrevios);
        verify(repoChat, atLeastOnce()).save(chat);
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void cierreVozInvalidoNoMutaHistorialNiConsultaProveedorNiEncola() {
        Chat chat = chatDeVoz(new Paciente());
        ResumenEntrevistaVoz vacio = new ResumenEntrevistaVoz(null, null, null, null, null,
                null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> chatService.finalizarEntrevistaVoz("1", "auth0|paciente",
                List.of(new TurnoVoz(AutorMensaje.PACIENTE, "Me duele la cabeza")), vacio));

        assertEquals(1, chat.getMensajes().size());
        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        verifyNoInteractions(triageIaClient, atencionHospitalService);
        verify(repoChat, never()).save(any());
    }

    @Test
    void cierreVozValidaHistorialPersistidoJuntoConLosTurnosPendientes() {
        Chat chat = chatDeVoz(new Paciente());
        var turnos = entrevistaConCierreSolicitado("Tengo dolor de cabeza desde ayer, dolor 8/10.");
        turnos.subList(0, turnos.size() - 1).forEach(turno -> chat.agregarMensaje(
                new Mensaje(turno.contenido(), turno.autor(), chat.getPaciente())));

        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", resultadoFinal(3, false)));
        chatService.finalizarEntrevistaVoz("1", "auth0|paciente", turnos.subList(turnos.size() - 1, turnos.size()), resumen(List.of()));

        assertTrue(chat.isFinalizado());
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
    }

    @Test
    void sinFallback_respuestasBrevesCompletasSolicitanCierreYNoRepitenPreguntas() throws Exception {
        Chat chat = chatDeVoz(new Paciente());
        List<TurnoVoz> turnos = List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Tengo dolor de cabeza y fiebre"),
                new TurnoVoz(AutorMensaje.BOT, "¿Desde cuándo tenés dolor de cabeza y fiebre?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "El dolor de cabeza empezó hace 12 horas; la fiebre empezó hace 12 horas y no medí la temperatura."),
                new TurnoVoz(AutorMensaje.BOT, "Del 0 al 10, cuanto te duele?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "8/10."),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar sobre lo que te pasa?"));
        turnos.forEach(turno -> chat.agregarMensaje(new Mensaje(turno.contenido(), turno.autor(), chat.getPaciente())));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo.", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), eq(chat)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        int mensajesPrevios = chat.getMensajes().size();
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "No, no tengo más síntomas. No tengo dificultad para respirar, dolor de pecho o confusion ni antecedentes, alergias o medicacion.");
        assertTrue(chat.isFinalizado());
        assertEquals(com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA, chat.getEstadoClasificacion());
        assertNotNull(chat.getResultadoTriageJson());
        assertEquals(mensajesPrevios + 2, chat.getMensajes().size());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        verify(repoChat).save(chat);
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), eq(chat));
    }

    @Test
    void aceptaCierreGeminiConInicioDesdeAnocheYLosDemasDatosCubiertos() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Fiebre de 39 grados y dolor de garganta", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Desde cuándo tenés fiebre y dolor de garganta?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("La fiebre empezó anoche y el dolor de garganta también empezó anoche.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("Del 0 al 10, ¿cuánto te duele la garganta?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("5/10.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente",
                "No tengo dificultad para respirar ni dolor de pecho. No tengo enfermedades ni alergias y no tomo medicamentos. No, no tengo más síntomas.");
        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        verify(triageIaClient).consultar(anyString(), anyString(), eq(true));
    }

    @Test
    void cierraConMotivoYContextoSinPreguntarAlarmasOAntecedentes() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Fiebre de 39 grados y dolor de garganta", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Desde cuándo tenés fiebre y dolor de garganta?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("La fiebre empezó anoche y el dolor de garganta también empezó anoche.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("Del 0 al 10, ¿cuánto te duele la garganta?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("5/10.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No tengo enfermedades previas ni alergias. No, no tengo más síntomas.");

        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        assertEquals("Listo", respuesta.respuesta().contenido());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(triageIaClient).consultar(anyString(), contains("La fiebre empezó anoche y el dolor de garganta también empezó anoche."), eq(true));
        verify(atencionHospitalService).finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class));
        verify(repoChat).save(chat);
    }

    @Test
    void falloDeTransporteEnLaPreguntaNoSeReintentaAutomaticamente() {
        Chat chat = prepararChat();
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenThrow(new ProveedorIaException(null));
        assertThrows(ProveedorIaException.class, () -> chatService.enviarMensaje("1", "auth0|paciente", "Tengo fiebre"));
        verify(triageIaClient, times(1)).consultarContinuacion(anyString(), anyString(), any());
        assertEquals(1, chat.getMensajes().size());
        verifyNoInteractions(atencionHospitalService);
    }

    @Test
    void moratonConInicioEvolucionContextoYAlarmasPermiteCerrarSinCorreccion() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Un moraton en la rodilla por un golpe hace 3 dias y va empeorando", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("Tomas medicacion o tenes alergias?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("No, ninguna.", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("Tenes dificultad para respirar o dolor de pecho?", AutorMensaje.BOT, null));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", resultadoFinal(3, false)));
        when(atencionHospitalService.finalizarTriageEIngresarACola(anyString(), any(), anyString(), any(Chat.class)))
                .thenReturn(new TiempoEstimadoAtencionResponse());
        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No tengo dificultad para respirar ni dolor de pecho. Me cuesta caminar por el moretón. No, no tengo más síntomas.");
        assertTrue(chat.isFinalizado());
        assertEquals("GEMINI", respuesta.origenRespuesta());
        verify(triageIaClient, never()).consultarContinuacion(anyString(), anyString(), any());
        verify(triageIaClient).consultar(anyString(), argThat(datos -> contieneTurnoJson(datos, "BOT",
                "Tenes dificultad para respirar o dolor de pecho?")
                && contieneTurnoJson(datos, "PACIENTE",
                "No tengo dificultad para respirar ni dolor de pecho. Me cuesta caminar por el moretón. No, no tengo más síntomas.")), eq(true));
    }

    @Test
    void cierreVozNormalizaResumenPeroConservaTranscripcionLiteralYContratoDeCola() throws Exception {
        Chat chat = chatDeVoz(new Paciente());
        String literal = "Tengo un moratón en la rodilla desde ayer, dolor 9/10 y empeora. Me cuesta hacer mis tareas por el moratón.";
        var clasificacion = new TriageResultDTO("moratón en rodilla", List.of("moratón"),
                "desde ayer", "empeora", 9, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Niega alergias", 3, false, "Evaluación presencial");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", clasificacion));
        chatService.finalizarEntrevistaVoz("1", "auth0|paciente", entrevistaConCierreSolicitado(literal), resumen(List.of()));
        var guardado = new ObjectMapper().readTree(chat.getResultadoTriageJson());
        assertEquals("moretón en rodilla", guardado.path("motivoConsulta").asText());
        assertEquals("moretón", guardado.path("sintomas").get(0).asText());
        assertEquals(9, guardado.path("intensidadDolor").intValue());
        assertTrue(chat.getMensajes().stream().anyMatch(m -> literal.equals(m.getContenido())));
        verify(atencionHospitalService).finalizarTriageEIngresarACola(eq("auth0|paciente"),
                eq(NivelDeGravedad.URGENTE), eq(chat.getResultadoTriageJson()), same(chat));
    }

    @Test
    void cierreTextoTambienNormalizaResumenSinModificarMensajeDelPaciente() throws Exception {
        Chat chat = prepararChat();
        String literal = "Un moratón en la rodilla hace 3 dias y va empeorando";
        chat.agregarMensaje(new Mensaje(literal, AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("No tengo antecedentes ni alergias", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        var clasificacion = new TriageResultDTO("moratón", List.of("moratón"), "hace 3 días", "empeora",
                null, List.of(), List.of(), List.of(), List.of(), "no informado", "no informado", 3, false, "Evaluación presencial");
        when(triageIaClient.consultar(anyString(), anyString(), eq(true)))
                .thenReturn(new TriageAiResponse(true, "Listo", clasificacion));
        chatService.enviarMensaje("1", "auth0|paciente", "No tengo dificultad para respirar ni dolor de pecho. Me cuesta caminar por el moretón. No, no tengo más síntomas.");
        assertEquals("moretón", new ObjectMapper().readTree(chat.getResultadoTriageJson()).path("motivoConsulta").asText());
        assertTrue(chat.getMensajes().stream().anyMatch(m -> literal.equals(m.getContenido())));
    }

    @Test
    void rechazoDeOtrosSintomasPermiteCuantificarElSintomaYaAfirmado() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Me duele la cabeza desde ayer", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Hay algo más que quieras contar sobre lo que te pasa?", AutorMensaje.BOT, null));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Del 0 al 10, cuánto te duele ahora?", null));

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No, no tengo más síntomas.");

        assertFalse(chat.isFinalizado());
        assertNull(chat.getResultadoTriageJson());
        assertEquals("¿Del 0 al 10, cuánto te duele ahora?", respuesta.respuesta().contenido());
        verify(triageIaClient).consultarContinuacion(
                contains("Una respuesta de que no tiene otros sintomas no impide cuantificar"),
                contains("No, no tengo más síntomas"), any());
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
    }

    @Test
    void noAUnaPreguntaDeAlergiasNoSeConfundeConElFinDeLaEntrevista() {
        Chat chat = prepararChat();
        chat.agregarMensaje(new Mensaje("Me duele la cabeza", AutorMensaje.PACIENTE, chat.getPaciente()));
        chat.agregarMensaje(new Mensaje("¿Tenés alergias?", AutorMensaje.BOT, null));
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Desde cuándo empezó el dolor de cabeza?", null));

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "No.");

        assertFalse(chat.isFinalizado());
        assertEquals("¿Desde cuándo empezó el dolor de cabeza?", respuesta.respuesta().contenido());
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
    }

    @Test
    void motivoAmbiguoPideAclararloSinEnumerarSintomas() {
        Chat chat = prepararChat();
        when(triageIaClient.consultarContinuacion(anyString(), anyString(), any()))
                .thenReturn(new TriageAiResponse(false, "¿Qué sentís exactamente?", null));

        var respuesta = chatService.enviarMensaje("1", "auth0|paciente", "Me siento mal.");

        assertFalse(chat.isFinalizado());
        assertEquals("¿Qué sentís exactamente?", respuesta.respuesta().contenido());
        verify(triageIaClient).consultarContinuacion(contains(ValidadorCierreEntrevistaVoz.instruccionDatosPendientes(
                List.of(new TurnoVoz(AutorMensaje.BOT, "Cual es el motivo de consulta?"),
                        new TurnoVoz(AutorMensaje.PACIENTE, "Me siento mal.")))), anyString(), any());
        verify(triageIaClient, never()).consultar(anyString(), anyString(), eq(true));
    }

    private List<TurnoVoz> entrevistaConCierreSolicitado(String primerTurno) {
        return List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, primerTurno),
                new TurnoVoz(AutorMensaje.PACIENTE, "No quiero seguir respondiendo."));
    }

    private Chat chatDeVoz(Paciente paciente) {
        Chat chat = new Chat(paciente);
        chat.setId(1L);
        chat.agregarMensaje(new Mensaje("Hola. Cual es el principal motivo de tu consulta hoy?", AutorMensaje.BOT, null));
        when(repoChat.findByIdAndPacienteUsuarioAuthId(1L, "auth0|paciente")).thenReturn(Optional.of(chat));
        when(repoChat.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return chat;
    }

    private boolean contieneTurnoJson(String datos, String autor, String contenido) {
        try {
            var turnos = new ObjectMapper().readTree(datos).get("turnos");
            if (turnos == null || !turnos.isArray()) return false;
            for (var turno : turnos) {
                if (autor.equals(turno.path("autor").stringValue())
                        && contenido.equals(turno.path("contenido").stringValue())) return true;
            }
            return false;
        } catch (Exception error) {
            return false;
        }
    }

    private boolean contieneResumenJson(String datos, String campo, String valor) {
        try {
            var resumen = new ObjectMapper().readTree(datos).get("resumenNoVerificado");
            return resumen != null && valor.equals(resumen.path(campo).stringValue());
        } catch (Exception error) {
            return false;
        }
    }

    private List<TurnoVoz> turnosDeVoz() {
        return List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Me duele la cabeza desde ayer y tengo fiebre de 39 grados desde ayer"),
                new TurnoVoz(AutorMensaje.BOT, "Del 0 al 10, cuanto te duele?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "Un 6 de 10. No tengo dificultad para respirar ni alergias."),
                new TurnoVoz(AutorMensaje.BOT, "Tenes antecedentes, tomas medicamentos o podria haber embarazo?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No tengo enfermedades previas, no tomo medicamentos y no hay posibilidad de embarazo."),
                new TurnoVoz(AutorMensaje.BOT, "¿Hay algo más que quieras contar sobre lo que te pasa?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "No, no tengo más síntomas."),
                new TurnoVoz(AutorMensaje.BOT, " "));
    }

    private List<TurnoVoz> entrevistaVozSinHechos() {
        return List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "pan rallado"),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué sentís exactamente?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "cocina"),
                new TurnoVoz(AutorMensaje.BOT, "¿Podés aclarar qué querés decir?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "siento un"),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué sensación estás tratando de describir?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "solsticio"),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué querés decir con esa expresión?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "aún"));
    }

    private List<TurnoVoz> entrevistaVozColoquialSinHechos() {
        return List.of(
                new TurnoVoz(AutorMensaje.PACIENTE, "Siento que todo da vueltas al incorporarme."),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué notás cuando te incorporás?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "cocina"),
                new TurnoVoz(AutorMensaje.BOT, "¿Podés aclarar esa palabra?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "siento un"),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué parte de la frase querías completar?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "solsticio"),
                new TurnoVoz(AutorMensaje.BOT, "¿Qué querés decir con esa expresión?"),
                new TurnoVoz(AutorMensaje.PACIENTE, "aún"));
    }

    private com.pretriage.backend.services.entrevista.ProgresoEntrevista progresoVoz(List<TurnoVoz> turnos) {
        var historial = new java.util.ArrayList<TurnoVoz>();
        historial.add(new TurnoVoz(AutorMensaje.BOT, "¿Cuál es el motivo de consulta hoy?"));
        historial.addAll(turnos);
        return com.pretriage.backend.services.entrevista.ProgresoEntrevista.evaluar(historial);
    }

    private ResumenEntrevistaVoz resumenSinHechos() {
        return new ResumenEntrevistaVoz("no informado", List.of(), "no informado", "no informado",
                null, List.of(), List.of(), List.of(), List.of(), "no informado",
                "No se obtuvo información clínica.");
    }

    private ResumenEntrevistaVoz resumen(List<String> signosAlarma) {
        return new ResumenEntrevistaVoz("dolor de cabeza con fiebre", List.of("dolor de cabeza", "fiebre"),
                "desde ayer", "se mantiene", 6, signosAlarma, List.of(), List.of(), List.of(), "no",
                "Niega dificultad respiratoria y alergias");
    }

    private ResumenEntrevistaVoz resumenEmocional() {
        return new ResumenEntrevistaVoz("malestar emocional", List.of("tristeza y angustia"),
                "desde ayer", "no informado", null, List.of(), List.of(), List.of(), List.of(),
                "no informado", "malestar emocional");
    }
}


