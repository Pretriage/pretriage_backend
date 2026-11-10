package com.pretriage.backend.services;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.exceptions.AtencionPendienteException;
import com.pretriage.backend.exceptions.ChatFinalizadoException;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.ChatNoEncontradoException;
import com.pretriage.backend.exceptions.EntradaChatNoClinicaException;
import com.pretriage.backend.exceptions.PacienteNoExisteException;
import com.pretriage.backend.exceptions.PacienteSancionadoException;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.mappers.MapperChat;
import com.pretriage.backend.mappers.MapperMensaje;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.EstadoRecuperacionChat;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.services.voz.ValidadorCierreEntrevistaVoz;
import com.pretriage.backend.services.entrevista.ProgresoEntrevista;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String MENSAJE_INICIAL = "¡Hola! Soy el asistente de PreTriage. Te voy a hacer algunas preguntas breves para registrar tus síntomas y "
            + "ayudar a organizar tu atención. No reemplazo una evaluación médica: si sentís que es una emergencia, "
            + "llamá al 107 o acercate a la guardia más cercana. Para empezar, ¿cuál es el principal motivo de tu consulta hoy?";
    private static final String ALCANCE_CONTRADICTORIO = "scope_conflicts_with_patient_evidence";
    public static final int MAX_MENSAJES_PACIENTE = LenguajeClinicoChat.MAX_RESPUESTAS_PACIENTE;

    private static final String FINAL_SYSTEM_PROMPT = """
            Para un resultado clínico, clasifica exclusivamente los datos admitidos del paciente.
            No diagnostiques ni inventes datos.
            Conserva los sintomas afirmados en sintomas y las negaciones en observaciones.
            Una negativa a agregar mas sintomas solo indica que el paciente no quiere agregar otros datos;
            no significa que haya negado signos de alarma que no menciono. No infieras su ausencia.
            Si no se informo un dato, dejalo como no informado y conserva la prioridad y los flags que
            correspondan a la evidencia disponible.
            Copia el inicio informado (por ejemplo "desde ayer") sin reemplazarlo por "hoy". Una duda
            sobre la fecha exacta no borra una referencia relativa que el paciente sí afirma: conserva
            literalmente expresiones como "no sé la fecha exacta; me siento triste desde que mi pareja se mudó
            a otra ciudad". No inventes una fecha absoluta ni infieras evolución a partir del inicio;
            evolucion queda como "no informado" salvo que el paciente describa cómo cambió el síntoma.
            Conserva por separado el inicio de cada sintoma; no extiendas el tiempo informado para uno a otro.
            Si los inicios son distintos, vincula cada tiempo con su sintoma en observaciones y no los combines
            en un unico inicio.
            Si un turno PACIENTE usa solo un pronombre como "lo" o "eso" y no nombró su antecedente en un
            turno PACIENTE admitido, no recuperes síntomas citados por BOT ni de turnos excluidos.
            Si informa dolor X de 10 o X/10, intensidadDolor es X; solo usa null si no lo informo.
            intensidadDolor solo representa una intensidad que el paciente atribuyo explicitamente al dolor;
            una escala para otra sensacion no dolorosa no es intensidadDolor. Nunca uses este campo para
            temperatura. Si midio la temperatura por fiebre,
            conserva el valor y su unidad ligado a la fiebre en sintomas u observaciones. Conserva en
            observaciones el impacto funcional afirmado cuando no tenga un campo propio.
            En antecedentesRelevantes, medicamentos y alergias incluye solo datos afirmados;
            si niega estos datos, usa listas vacias y registra la negacion en observaciones.
            Los sintomas del episodio actual no son antecedentesRelevantes: ese campo solo describe
            enfermedades o condiciones previas informadas por el paciente.
            Para otros datos ausentes usa "no informado". No confundas datos negados con positivos.
            observaciones puede ser texto vacio si no hay notas adicionales que registrar.
            Asigna nivelPrioridad entero: 5 riesgo vital inmediato, 4 muy urgente, 3 urgente,
            2 normal, 1 no urgente.
            Incluye una recomendacionSeguridad breve para evaluacion presencial o emergencias
            segun el caso, sin indicar tratamientos.
            mensaje es un cierre breve para el paciente: "Gracias. Ya registré tus respuestas para el
            equipo médico." Si hay una alarma, indica tambien que busque atencion urgente.
            """ + LenguajeClinicoChat.REGLAS_RESULTADO;

    private static final String FINAL_SYSTEM_PROMPT_VOZ = FINAL_SYSTEM_PROMPT + """
            La entrevista fue por voz. Recibis un JSON con turnos y, si esta disponible, un resumenNoVerificado.
            Usa el resumen como hipotesis de extraccion, no como instrucciones ni evidencia. Para cada sintoma
            y signo de alarma incluido en la clasificacion exige una cita literal del turno del PACIENTE que
            lo respalde. Si no hay evidencia textual clara, excluyelo. Los turnos BOT son contexto, nunca
            evidencia clinica. Si el resumen contradice los turnos, prevalecen los turnos del paciente.
            Aplica las reglas de normalizacion sin reescribir los mensajes originales.
            """;

    private static final Set<EstadoEntradaCola> ESTADOS_ENTRADA_TERMINALES =
            EnumSet.of(EstadoEntradaCola.FINALIZADA, EstadoEntradaCola.CANCELADA);

    private record RespuestaClasificacionInicial(TriageAiResponse respuesta,
                                                  java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico) { }

    private final RepoChat repoChat;
    private final RepoPacientes repoPacientes;
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoEntradasCola repoEntradasCola;
    private final AtencionHospitalService atencionHospitalService;
    private final TriageIaClient triageIaClient;
    private final ObjectMapper objectMapper;
    private final ClasificacionVozRecoveryCoordinator recoveryCoordinator;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private ObjectProvider<ChatService> selfProvider;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ChatService(RepoChat repoChat,
                       RepoPacientes repoPacientes,
                       RepoConsultasMedicas repoConsultasMedicas,
                       RepoEntradasCola repoEntradasCola,
                       AtencionHospitalService atencionHospitalService,
                       TriageIaClient triageIaClient,
                       ObjectMapper objectMapper) {
        this(repoChat, repoPacientes, repoConsultasMedicas, repoEntradasCola, atencionHospitalService,
                triageIaClient, objectMapper, new ClasificacionVozRecoveryCoordinator());
    }

    @Autowired
    public ChatService(RepoChat repoChat,
                       RepoPacientes repoPacientes,
                       RepoConsultasMedicas repoConsultasMedicas,
                       RepoEntradasCola repoEntradasCola,
                       AtencionHospitalService atencionHospitalService,
                       TriageIaClient triageIaClient,
                       ObjectMapper objectMapper,
                       ClasificacionVozRecoveryCoordinator recoveryCoordinator) {
        this.repoChat = repoChat;
        this.repoPacientes = repoPacientes;
        this.repoConsultasMedicas = repoConsultasMedicas;
        this.repoEntradasCola = repoEntradasCola;
        this.atencionHospitalService = atencionHospitalService;
        this.triageIaClient = triageIaClient;
        this.objectMapper = objectMapper;
        this.recoveryCoordinator = recoveryCoordinator;
    }

    /**
     * Siempre crea un chat nuevo. Antes valida que el paciente no tenga una atención pendiente
     * (consulta con chat vinculado cuya entrada de cola no es terminal) y luego cierra los chats
     * abiertos previos, de modo que quede a lo sumo un chat abierto por paciente.
     */
    @Transactional
    public ChatDTO iniciarChat(String idPaciente) {
        Paciente paciente = repoPacientes.findByUsuarioAuthId(idPaciente)
                .orElseThrow(PacienteNoExisteException::new);

        validarSinAtencionPendiente(paciente);
        cerrarChatsAbiertos(idPaciente);
        validarSinAtencionPendiente(paciente);

        Chat chat = new Chat(paciente);
        consultaActual(paciente).ifPresent(c -> chat.setConsultaOrigenId(c.getId()));
        chat.agregarMensaje(new Mensaje(MENSAJE_INICIAL, AutorMensaje.BOT, null));
        chat.avanzarVersionEstado();
        repoChat.save(chat);
        return MapperChat.toDTO(chat);
    }

    private void validarSinAtencionPendiente(Paciente paciente) {
        List<ConsultaMedica> consultasConChat = repoConsultasMedicas.findByPacienteIdAndChatIsNotNull(paciente.getId());
        boolean hayAtencionPendiente = consultasConChat.stream()
                .map(consulta -> repoEntradasCola.findByConsultaMedicaId(consulta.getId()))
                .flatMap(Optional::stream)
                .map(EntradaCola::getEstado)
                .anyMatch(estado -> !ESTADOS_ENTRADA_TERMINALES.contains(estado));
        if (hayAtencionPendiente) {
            throw new AtencionPendienteException();
        }
    }

    private void cerrarChatsAbiertos(String idPaciente) {
        List<Chat> chatsAbiertos = repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse(idPaciente);
        if (chatsAbiertos.isEmpty()) {
            return;
        }
        chatsAbiertos.stream().sorted(java.util.Comparator.comparing(Chat::getId)).forEach(anterior -> {
            Chat chatAbierto = repoChat.findForUpdate(anterior.getId(), idPaciente).orElseThrow(ChatNoEncontradoException::new);
            if (entityManager != null) entityManager.refresh(chatAbierto, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (chatAbierto.isFinalizado()) return;
            chatAbierto.setFinalizado(true);
            chatAbierto.setTurnoTextoEnCurso(false);
            chatAbierto.setEstadoTurnoTexto(com.pretriage.backend.model.chat.EstadoTurnoTexto.COMPLETADA);
            if (chatAbierto.getResultadoTriageJson() == null && chatAbierto.getMensajes().stream()
                    .anyMatch(m -> m.getAutor() == AutorMensaje.PACIENTE)) {
                chatAbierto.setCierreParcial(true);
                chatAbierto.setEstadoClasificacion(EstadoClasificacionChat.PARCIAL);
            }
            limpiarRecuperacion(chatAbierto);
            chatAbierto.avanzarVersionEstado();
        });
        repoChat.saveAll(chatsAbiertos);
    }

    @Transactional(readOnly = true)
    public ChatDTO obtenerChat(String idChat, String idPaciente) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        return MapperChat.toDTO(chat);
    }

    /** Voice context includes bot turns but excludes patient turns rejected by the scope gate. */
    @Transactional(readOnly = true)
    public List<MensajeDTO> obtenerHistorialClinicoVoz(String idChat, String idPaciente) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        return mensajesLiteralesVoz(chat).stream().map(MapperMensaje::toDTO).toList();
    }

    /** Returns the full local progress projection, including exclusion flags; never provider input. */
    @Transactional(readOnly = true)
    public List<ProgresoEntrevista.Turno> obtenerTurnosParaProgresoVoz(String idChat, String idPaciente) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        return chat.getMensajes().stream()
                .map(mensaje -> new ProgresoEntrevista.Turno(mensaje.getAutor(), mensaje.getContenido(),
                        Boolean.TRUE.equals(mensaje.getExcluidoDelTriage())))
                .toList();
    }

    @Transactional(noRollbackFor = ProveedorIaException.class)
    public ChatTurnResponse enviarMensaje(String idChat, String idPaciente, String contenido) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        validarTurnoTextoNoBloqueado(chat);
        if (chat.isFinalizado()) throw new ChatFinalizadoException();
        Mensaje mensajePaciente = new Mensaje(contenido.trim(), AutorMensaje.PACIENTE, chat.getPaciente());
        chat.agregarMensaje(mensajePaciente);
        return procesarTurnoTexto(chat, idPaciente, mensajePaciente, false);
    }

    /** Processes the already durable patient turn claimed by TextTurnService. */
    @Transactional(noRollbackFor = ProveedorIaException.class)
    public ChatTurnResponse procesarTurnoTextoPersistido(String idChat, String idPaciente,
                                                          String solicitudId, int intento) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente)
                .orElseThrow(ChatNoEncontradoException::new);
        if (chat.getEstadoTurnoTexto() != com.pretriage.backend.model.chat.EstadoTurnoTexto.PENDIENTE
                || !solicitudId.equals(chat.getSolicitudTurnoTextoId())
                || chat.getIntentosTurnoTexto() != intento || !chat.isTurnoTextoEnCurso()) {
            throw new IllegalStateException("El turno de texto no está pendiente");
        }
        Mensaje mensajePaciente = chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.PACIENTE && solicitudId.equals(m.getSolicitudId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay turno de paciente persistido"));
        if (chat.isFinalizado()) throw new ChatFinalizadoException();
        validarConsultaVigente(chat, idPaciente);
        ChatTurnResponse response = procesarTurnoTexto(chat, idPaciente, mensajePaciente, true);
        chat.setEstadoTurnoTexto(com.pretriage.backend.model.chat.EstadoTurnoTexto.COMPLETADA);
        chat.setTurnoTextoEnCurso(false);
        chat.setTurnoTextoIniciadoEn(null);
        chat.setErrorTurnoTexto(null);
        if (chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE) limpiarRecuperacion(chat);
        chat.avanzarVersionEstado();
        repoChat.save(chat);
        return response;
    }

    private ChatTurnResponse procesarTurnoTexto(Chat chat, String idPaciente,
                                                Mensaje mensajePaciente, boolean turnoPersistido) {
        if (chat.isFinalizado() && (!turnoPersistido
                || chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE)) {
            throw new ChatFinalizadoException();
        }
        boolean clasificacionSolicitada = false;
        ProgresoEntrevista progreso = progresoEntrevista(chat);
        try {
            // An unparseable answer to the one pending question stays excluded. The
            // progress projection may count it locally, but classifier input filters it out.
            if (requiereAclaracionDeDato(progreso)
                    && !progreso.ultimoTurnoPacienteTieneHechos()
                    && esRespuestaPuraConUnidadIncompatible(mensajePaciente.getContenido())) {
                mensajePaciente.setExcluidoDelTriage(true);
            }
            progreso = progresoEntrevista(chat);
            boolean cierreRequerido = progreso.puedeCerrar();
            if (cierreRequerido && !hayTurnoPacienteIncluido(chat)) {
                return cerrarSinHechosClinicos(chat, progreso);
            }
            clasificacionSolicitada = cierreRequerido;
            if (cierreRequerido) iniciarClasificacion(chat);
            if (cierreRequerido && turnoPersistido) {
                prepararClasificacionDurable(chat, false);
                return new ChatTurnResponse(null, null, "PROCESAMIENTO_PENDIENTE");
            }
            long inicioConsulta = System.nanoTime();
            TriageAiResponse respuesta;
            java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico = java.util.Optional.empty();
            if (cierreRequerido) {
                String datos = construirDatosParaClasificacion(chat);
                String promptFinal = promptFinal(progreso, false);
                var clasificacion = consultarClasificacionFinal(chat, datos, promptFinal);
                respuesta = clasificacion.respuesta();
                rechazoSemantico = clasificacion.rechazoSemantico();
                respuesta = corregirClasificacionSiHaceFalta(chat, respuesta, datos, inicioConsulta, rechazoSemantico, promptFinal);
            } else {
                respuesta = consultarPregunta(chat, inicioConsulta);
            }
            if (estadoEntrada(respuesta) != EstadoEntradaChat.RELEVANTE) {
                mensajePaciente.setExcluidoDelTriage(true);
                if (clasificacionSolicitada) {
                    if (permiteCierreAdministrativo(progreso)) {
                        return cerrarSinHechosClinicos(chat, progreso);
                    }
                    deshacerCierreTentativo(chat);
                    return registrarRespuestaDeAlcance(chat, estadoEntrada(respuesta), null);
                }
                return registrarRespuestaDeAlcance(chat, estadoEntrada(respuesta), respuesta);
            }
            boolean respuestaValida = respuesta != null && respuesta.mensaje() != null
                    && !respuesta.mensaje().isBlank()
                    && (cierreRequerido ? respuesta.resultado() != null : respuesta.resultado() == null && !respuesta.finalizado());
            if (!respuestaValida) throw respuestaIaInvalida(chat, "invalid_response");
            if (respuesta.finalizado() && !esResultadoFinalValido(respuesta.resultado())) {
                throw respuestaIaInvalida(chat, "invalid_classification");
            }
            if (respuesta.finalizado() && !puedeAceptarCierreIa(chat, respuesta, progreso)) {
                throw respuestaIaInvalida(chat, "premature_or_unsafe_closure");
            }
            if (cierreRequerido && !respuesta.finalizado()) {
                throw respuestaIaInvalida(chat, "required_closure_missing");
            }
            return registrarRespuestaBot(chat, idPaciente, respuesta, "GEMINI", progreso, null);
        } catch (RuntimeException error) {
            if (clasificacionSolicitada) {
                prepararClasificacionDurable(chat, false);
                chat.setIntentosClasificacionAutomaticos(1);
                registrarFalloClasificacion(chat, error, 5000, 30000);
                return new ChatTurnResponse(null, null, "PROCESAMIENTO_PENDIENTE");
            } else if (!turnoPersistido) {
                // An unfinished interview still permits retrying an undelivered answer.
                chat.getMensajes().remove(mensajePaciente);
            }
            throw error;
        }
    }

    /**
     * Guarda los turnos transcriptos de una sesion de voz que termino sin completar la
     * entrevista, para que el paciente pueda continuar por texto o por voz.
     */
    @Transactional
    public void registrarTurnosVoz(String idChat, String idPaciente, List<TurnoVoz> turnos) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
        validarTurnoTextoBloqueadoParaVoz(chat);
        if (chat.isFinalizado()) {
            throw new ChatFinalizadoException();
        }
        agregarTurnosVoz(chat, turnos);
        chat.avanzarVersionEstado();
        repoChat.save(chat);
    }

    /** Leaving voice mode is not an interview closure or an ASR confirmation boundary. */
    @Transactional
    public void guardarVozInterrumpidaSinFinalizar(String idChat, String idPaciente,
                                                   List<TurnoVoz> confirmados, String fragmento) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
        if (chat.isFinalizado()) return;
        validarTurnoTextoBloqueadoParaVoz(chat);
        validarConsultaVigente(chat, idPaciente);
        agregarTurnosVoz(chat, confirmados);
        agregarFragmentoVozExcluido(chat, fragmento);
        chat.avanzarVersionEstado();
        repoChat.save(chat);
    }

    private void agregarFragmentoVozExcluido(Chat chat, String fragmento) {
        if (fragmento == null || fragmento.isBlank()) return;
        Mensaje parcial = new Mensaje(fragmento.trim(), AutorMensaje.PACIENTE, chat.getPaciente());
        parcial.setExcluidoDelTriage(true);
        chat.agregarMensaje(parcial);
    }

    /**
     * Checks the scope of a completed voice snapshot without persisting it. This preflight
     * runs before the voice session sends its goodbye or closes its live connection.
     */
    @Transactional(readOnly = true)
    public void validarAlcanceCierreVoz(String idChat, String idPaciente,
                                        List<TurnoVoz> turnos, ResumenEntrevistaVoz resumen) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        validarTurnoTextoBloqueadoParaVoz(chat);
        if (chat.isFinalizado()) throw new ChatFinalizadoException();
        List<TurnoVoz> entrevistaCompleta = new ArrayList<>(turnosLiteralesVoz(chat));
        if (turnos != null) entrevistaCompleta.addAll(turnos);
        ValidadorCierreEntrevistaVoz.validarConContexto(turnosParaProgresoVoz(chat, turnos), resumen)
                .ifPresent(error -> { throw new IllegalArgumentException(error); });
        EstadoEntradaChat estado = triageIaClient.consultarAlcance(
                construirDatosVozParaClasificacion(entrevistaCompleta, resumen),
                java.time.Duration.ofSeconds(45));
        ProgresoEntrevista progreso = progresoEntrevista(chat, turnos);
        if (estado != EstadoEntradaChat.RELEVANTE && !permiteCierreAdministrativo(progreso)) {
            throw new EntradaChatNoClinicaException(estado);
        }
    }

    /**
     * Cierra una entrevista conducida por Gemini Live: guarda la transcripcion y pide a
     * Gemini la preclasificacion a partir del resumen recolectado.
     */
    public ChatTurnResponse finalizarEntrevistaVoz(String idChat, String idPaciente,
                                                  List<TurnoVoz> turnos, ResumenEntrevistaVoz resumen) {
        prepararCierreVoz(idChat, idPaciente, turnos, resumen);
        return ejecutarRecuperacionVoz(idChat, idPaciente, resumen);
    }

    /** Literal-only close used when Live omits its tool but the backend confirms closure. */
    public ChatTurnResponse finalizarEntrevistaVozSinResumen(String idChat, String idPaciente,
                                                             List<TurnoVoz> turnos) {
        prepararCierreVoz(idChat, idPaciente, turnos, null);
        return ejecutarRecuperacionVoz(idChat, idPaciente, null);
    }

    /** An already accepted voice closure may retain late facts with explicitly unknown dimensions. */
    public ChatTurnResponse finalizarEntrevistaVozConfirmadaParcial(String idChat, String idPaciente,
                                                                   List<TurnoVoz> turnos) {
        prepararCierreVoz(idChat, idPaciente, turnos, null, true);
        return ejecutarRecuperacionVoz(idChat, idPaciente, null);
    }

    private void prepararCierreVoz(String idChat, String idPaciente, List<TurnoVoz> turnos,
                                   ResumenEntrevistaVoz resumen) {
        prepararCierreVoz(idChat, idPaciente, turnos, resumen, false);
    }

    private void prepararCierreVoz(String idChat, String idPaciente, List<TurnoVoz> turnos,
                                   ResumenEntrevistaVoz resumen, boolean cierreYaConfirmado) {
        enTransaccion(() -> {
            Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente)
                    .orElseThrow(ChatNoEncontradoException::new);
            validarTurnoTextoBloqueadoParaVoz(chat);
            if (chat.isFinalizado()) throw new ChatFinalizadoException();
            if (resumen != null) {
                ValidadorCierreEntrevistaVoz.validarConContexto(turnosParaProgresoVoz(chat, turnos), resumen)
                        .ifPresent(error -> { throw new IllegalArgumentException(error); });
            } else if (!cierreYaConfirmado && !progresoEntrevista(chat, turnos).puedeCerrar()) {
                throw new IllegalArgumentException("La entrevista no reúne las condiciones para cerrar sin resumen");
            }
            agregarTurnosVoz(chat, turnos == null ? List.of() : turnos);
            iniciarClasificacion(chat);
            chat.setCierreParcial(cierreYaConfirmado);
            prepararClasificacionDurable(chat, true);
            repoChat.save(chat);
        });
    }

    private ChatTurnResponse ejecutarRecuperacionVoz(String idChat, String idPaciente,
                                                      ResumenEntrevistaVoz resumen) {
        // Try once immediately; all later attempts are durable and never sleep in the WS worker.
        Integer intento = enTransaccion(() -> {
            Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
            if (chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE
                    || !chat.isRecuperacionAutomaticaClasificacion() || chat.isClasificacionAutomaticaEnCurso()) return 0;
            chat.setIntentosClasificacionAutomaticos(chat.getIntentosClasificacionAutomaticos() + 1);
            chat.setClasificacionAutomaticaEnCurso(true);
            chat.setClasificacionIniciadaEn(java.time.LocalDateTime.now());
            chat.setProximoReintentoEn(null);
            chat.avanzarVersionEstado();
            return chat.getIntentosClasificacionAutomaticos();
        });
        if (intento != null && intento > 0) {
            try {
                ChatService service = selfProvider == null ? this : selfProvider.getObject();
                return service.procesarClasificacionDurable(idChat, idPaciente, intento);
            } catch (RuntimeException error) {
                enTransaccion(() -> {
                    Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
                    if (chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE
                            || chat.getIntentosClasificacionAutomaticos() != intento) return;
                    registrarFalloClasificacion(chat, error, 5000, 30000);
                });
            }
        }
        return new ChatTurnResponse(null, null, "PROCESAMIENTO_PENDIENTE");
    }

    private ChatTurnResponse respuestaFinalExistente(Chat chat) {
        Mensaje respuesta = chat.getMensajes().stream()
                .filter(m -> m.getAutor() == AutorMensaje.BOT)
                .reduce((primero, ultimo) -> ultimo).orElse(null);
        return new ChatTurnResponse(respuesta == null ? null : MapperMensaje.toDTO(respuesta),
                null, "GEMINI");
    }

    private <T> T enTransaccion(Supplier<T> operation) {
        if (transactionManager == null) return operation.get();
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private void enTransaccion(Runnable operation) {
        enTransaccion(() -> { operation.run(); return null; });
    }

    /** Close a failed voice interview exactly once, including failures before the provider call. */
    @Transactional
    public void registrarCierreVozFallido(String idChat, String idPaciente, List<TurnoVoz> turnos) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        validarTurnoTextoBloqueadoParaVoz(chat);
        if (chat.isFinalizado()) return;
        agregarTurnosVoz(chat, turnos == null ? List.of() : turnos);
        chat.setCierreParcial(true);
        prepararClasificacionDurable(chat, true);
        repoChat.save(chat);
    }

    /** Retains an interrupted Live turn without ever classifying an open ASR fragment. */
    @Transactional
    public ChatDTO recuperarVozInterrumpida(String idChat, String idPaciente, List<TurnoVoz> confirmados,
                                           String fragmento, boolean agotado, ProveedorIaException fallo) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
        if (chat.isFinalizado()) return MapperChat.toDTO(chat);
        validarTurnoTextoBloqueadoParaVoz(chat);
        validarConsultaVigente(chat, idPaciente);
        agregarTurnosVoz(chat, confirmados == null ? List.of() : confirmados);
        agregarFragmentoVozExcluido(chat, fragmento);
        if (agotado || (fallo != null && fallo.getCategoria() == ProveedorIaException.Categoria.CONFIGURACION)) {
            cerrarParcialEntrevista(chat, true);
        } else {
            Mensaje pendiente = null;
            for (Mensaje mensaje : chat.getMensajes()) {
                if (Boolean.TRUE.equals(mensaje.getExcluidoDelTriage())) continue;
                if (mensaje.getAutor() == AutorMensaje.PACIENTE) pendiente = mensaje;
                else if (mensaje.getAutor() == AutorMensaje.BOT) pendiente = null;
            }
            if (pendiente != null) {
                if (pendiente.getSolicitudId() == null) pendiente.setSolicitudId(java.util.UUID.randomUUID().toString());
                chat.setSolicitudTurnoTextoId(pendiente.getSolicitudId());
                chat.setIntentosTurnoTexto(0);
                chat.setFallosInternosTurnoTexto(fallo != null && fallo.isApiNoDisponible() ? 0 : 1);
                chat.setTurnoTextoEnCurso(false);
                boolean api = fallo != null && fallo.isApiNoDisponible();
                chat.setEstadoTurnoTexto(api ? com.pretriage.backend.model.chat.EstadoTurnoTexto.NO_DISPONIBLE
                        : com.pretriage.backend.model.chat.EstadoTurnoTexto.PENDIENTE);
                chat.setEstadoRecuperacion(api ? EstadoRecuperacionChat.ESPERANDO_API : EstadoRecuperacionChat.REINTENTANDO);
                java.time.Duration demora = api ? java.time.Duration.ofSeconds(30) : java.time.Duration.ofSeconds(2);
                if (api && fallo.getRetryAfter() != null && fallo.getRetryAfter().compareTo(demora) > 0) demora = fallo.getRetryAfter();
                chat.setProximoReintentoEn(java.time.LocalDateTime.now().plus(demora));
                chat.setFallosDisponibilidad(api ? 1 : 0);
            } else if (fallo != null && !fallo.isApiNoDisponible()) {
                cerrarParcialEntrevista(chat, true);
            }
        }
        chat.avanzarVersionEstado();
        repoChat.save(chat);
        return MapperChat.toDTO(chat);
    }

    private void iniciarClasificacion(Chat chat) {
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(EstadoClasificacionChat.PENDIENTE);
    }

    private Optional<ConsultaMedica> consultaActual(Paciente paciente) {
        return repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(paciente.getId(),
                EnumSet.complementOf(EnumSet.of(EstadoConsulta.PENDIENTE,
                        EstadoConsulta.CANCELADA, EstadoConsulta.FINALIZADA)));
    }

    private void validarConsultaVigente(Chat chat, String idPaciente) {
        var ultimo = repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc(idPaciente);
        if (ultimo.isPresent() && !ultimo.get().getId().equals(chat.getId())) throw new ChatFinalizadoException();
        if (chat.getConsultaOrigenId() == null) {
            consultaActual(chat.getPaciente()).filter(c -> c.getFechaHoraCreacion() == null
                    || chat.getFechaHoraCreacion() == null
                    || !c.getFechaHoraCreacion().isAfter(chat.getFechaHoraCreacion()))
                    .ifPresent(c -> chat.setConsultaOrigenId(c.getId()));
        } else {
            ConsultaMedica consulta = repoConsultasMedicas.findById(chat.getConsultaOrigenId())
                    .orElseThrow(ChatFinalizadoException::new);
            if (consulta.getEstadoConsulta() == EstadoConsulta.CANCELADA
                    || consulta.getEstadoConsulta() == EstadoConsulta.FINALIZADA
                    || !consulta.getPaciente().getId().equals(chat.getPaciente().getId())) throw new ChatFinalizadoException();
        }
    }

    private void prepararClasificacionDurable(Chat chat, boolean voz) {
        iniciarClasificacion(chat);
        chat.setRecuperacionAutomaticaClasificacion(true);
        chat.setClasificacionAutomaticaVoz(voz);
        chat.setIntentosClasificacionAutomaticos(0);
        chat.setFallosInternosClasificacion(0);
        chat.setClasificacionAutomaticaEnCurso(false);
        chat.setClasificacionIniciadaEn(null);
        chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
        chat.setProximoReintentoEn(java.time.LocalDateTime.now());
        chat.setFallosDisponibilidad(0);
        chat.avanzarVersionEstado();
    }

    static void limpiarRecuperacion(Chat chat) {
        chat.setEstadoRecuperacion(EstadoRecuperacionChat.SIN_RECUPERACION);
        chat.setProximoReintentoEn(null);
        chat.setClasificacionAutomaticaEnCurso(false);
        chat.setClasificacionIniciadaEn(null);
        chat.setRecuperacionAutomaticaClasificacion(false);
        chat.setFallosDisponibilidad(0);
    }

    void registrarFalloClasificacion(Chat chat, RuntimeException error, long demoraInterna, long demoraApi) {
        chat.setClasificacionAutomaticaEnCurso(false);
        chat.setClasificacionIniciadaEn(null);
        if (RecuperacionChatPolicy.api(error)) RecuperacionChatPolicy.programar(chat, error, demoraInterna, demoraApi);
        else {
            chat.setFallosInternosClasificacion(chat.getFallosInternosClasificacion() + 1);
            if (RecuperacionChatPolicy.configuracion(error) || chat.getFallosInternosClasificacion() >= 2) marcarParcial(chat);
            else RecuperacionChatPolicy.programar(chat, error, demoraInterna, demoraApi);
        }
        chat.avanzarVersionEstado();
        repoChat.save(chat);
    }

    /** Called with the row lock held after the bounded text budget is exhausted. */
    void cerrarParcialTexto(Chat chat) {
        cerrarParcialEntrevista(chat, false);
    }

    private void cerrarParcialEntrevista(Chat chat, boolean voz) {
        chat.setCierreParcial(true);
        chat.setEstadoTurnoTexto(com.pretriage.backend.model.chat.EstadoTurnoTexto.COMPLETADA);
        chat.setTurnoTextoEnCurso(false);
        chat.setTurnoTextoIniciadoEn(null);
        if (hayTurnoPacienteIncluido(chat)) prepararClasificacionDurable(chat, voz);
        else marcarParcial(chat);
    }

    /** Terminal processing outcome, never an invented TriageResultDTO or queue update. */
    void marcarParcial(Chat chat) {
        chat.setFinalizado(true);
        chat.setCierreParcial(true);
        chat.setEstadoClasificacion(EstadoClasificacionChat.PARCIAL);
        chat.setResultadoTriageJson(null);
        limpiarRecuperacion(chat);
        vincularConsultaParcial(chat);
        chat.avanzarVersionEstado();
    }

    private void vincularConsultaParcial(Chat chat) {
        Optional<ConsultaMedica> candidata = chat.getConsultaOrigenId() == null
                ? consultaActual(chat.getPaciente()) : repoConsultasMedicas.findById(chat.getConsultaOrigenId());
        Optional<ConsultaMedica> consulta = candidata.flatMap(c -> bloquearConsulta(c.getId()));
        consulta.filter(c -> c.getEstadoConsulta() != EstadoConsulta.CANCELADA
                        && c.getEstadoConsulta() != EstadoConsulta.FINALIZADA)
                .filter(c -> c.getPaciente().getId().equals(chat.getPaciente().getId()))
                .filter(c -> c.getChat() == null || c.getChat().getId().equals(chat.getId()))
                .filter(c -> c.getFechaHoraCreacion() == null || chat.getFechaHoraCreacion() == null
                        || !c.getFechaHoraCreacion().isAfter(chat.getFechaHoraCreacion()))
                .ifPresent(c -> { c.setChat(chat); repoConsultasMedicas.save(c); });
    }

    private Optional<ConsultaMedica> bloquearConsulta(Long id) {
        return repoConsultasMedicas.findByIdForUpdate(id).map(consulta -> {
            if (entityManager != null) entityManager.refresh(consulta, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            return consulta;
        });
    }

    /** Enrol only active legacy failures, preserving the original message and request ID. */
    @Transactional
    public boolean recuperarTurnoLegacy(String idChat, String idPaciente) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
        if (chat.isFinalizado() || chat.isRecuperacionLegacyAplicada()
                || chat.getEstadoTurnoTexto() != com.pretriage.backend.model.chat.EstadoTurnoTexto.NO_DISPONIBLE
                || chat.getSolicitudTurnoTextoId() == null) return false;
        validarConsultaVigente(chat, idPaciente);
        if (chat.getConsultaOrigenId() == null) return false;
        chat.setRecuperacionLegacyAplicada(true);
        chat.setFallosInternosTurnoTexto(0);
        chat.setTurnoTextoEnCurso(false);
        chat.setTurnoTextoIniciadoEn(null);
        chat.setEstadoTurnoTexto(com.pretriage.backend.model.chat.EstadoTurnoTexto.PENDIENTE);
        chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
        chat.setProximoReintentoEn(java.time.LocalDateTime.now());
        chat.avanzarVersionEstado();
        return true;
    }

    private void validarTurnoTextoBloqueadoParaVoz(Chat chat) {
        var estado = chat.getEstadoTurnoTexto();
        if (estado == com.pretriage.backend.model.chat.EstadoTurnoTexto.PENDIENTE
                || estado == com.pretriage.backend.model.chat.EstadoTurnoTexto.NO_DISPONIBLE) {
            throw new IllegalStateException("No se puede usar voz mientras el turno de texto no esté disponible");
        }
    }

    private void validarTurnoTextoNoBloqueado(Chat chat) {
        var estado = chat.getEstadoTurnoTexto();
        if (estado == com.pretriage.backend.model.chat.EstadoTurnoTexto.PENDIENTE
                || estado == com.pretriage.backend.model.chat.EstadoTurnoTexto.NO_DISPONIBLE) {
            throw new ConflictoDeEstadoException("El turno de texto actual no permite enviar otro mensaje");
        }
    }

    private void deshacerCierreTentativo(Chat chat) {
        chat.setFinalizado(false);
        chat.setEstadoClasificacion(EstadoClasificacionChat.NO_SOLICITADA);
        chat.setResultadoTriageJson(null);
    }

    private ChatTurnResponse cerrarSinHechosClinicos(Chat chat, ProgresoEntrevista progreso) {
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(EstadoClasificacionChat.NO_SOLICITADA);
        limpiarRecuperacion(chat);
        chat.setResultadoTriageJson(null);
        chat.avanzarVersionEstado();
        log.info("Chat closed without classification chatId={} reason={} clasificacion=false",
                chat.getId(), progreso.motivoCierre().name());
        Mensaje respuesta = new Mensaje(
                "No pude completar una entrevista de salud con la información disponible. Si necesitás ayuda, podés iniciar una nueva consulta y contarme qué te está pasando.",
                AutorMensaje.BOT, null);
        chat.agregarMensaje(respuesta);
        repoChat.save(chat);
        return new ChatTurnResponse(MapperMensaje.toDTO(respuesta), null, "CIERRE_SIN_DATOS");
    }

    private ChatTurnResponse registrarRespuestaDeAlcance(Chat chat, EstadoEntradaChat estado,
                                                         TriageAiResponse propuesta) {
        String mensaje = estado == EstadoEntradaChat.FUERA_DE_CONTEXTO
                ? "Puedo ayudarte con una consulta de salud. ¿Qué te está pasando?"
                : propuesta != null && propuesta.mensaje() != null && !propuesta.mensaje().isBlank()
                    ? propuesta.mensaje().trim()
                    : "No entendí bien tu consulta. ¿Podés contarme con tus palabras qué sentís?";
        Mensaje respuesta = new Mensaje(mensaje, AutorMensaje.BOT, null);
        chat.agregarMensaje(respuesta);
        repoChat.save(chat);
        return new ChatTurnResponse(MapperMensaje.toDTO(respuesta), null, "CONTROL_CONTEXTO");
    }

    private EstadoEntradaChat estadoEntrada(TriageAiResponse respuesta) {
        return respuesta == null || respuesta.estadoEntrada() == null
                ? EstadoEntradaChat.RELEVANTE : respuesta.estadoEntrada();
    }

    private void marcarClasificacionFallida(Chat chat) {
        chat.setFinalizado(true);
        chat.setEstadoClasificacion(EstadoClasificacionChat.FALLIDA);
        chat.setRecuperacionAutomaticaClasificacion(false);
        chat.setClasificacionAutomaticaEnCurso(false);
        repoChat.save(chat);
    }

    private String errorClasificacionFinal(Chat chat, TriageAiResponse respuesta) {
        if (respuesta != null && estadoEntrada(respuesta) != EstadoEntradaChat.RELEVANTE) {
            return progresoEntrevista(chat).tieneHechosClinicos() ? ALCANCE_CONTRADICTORIO : null;
        }
        if (respuesta == null || !respuesta.finalizado() || respuesta.mensaje() == null
                || respuesta.mensaje().isBlank() || !esResultadoFinalValido(respuesta.resultado())) {
            return "invalid_final_classification";
        }
        var resultado = respuesta.resultado();
        // Final alarm claims are validated against patient citations before reaching this layer;
        // they can corroborate an alarm even when the patient used different wording.
        boolean alarma = ValidadorCierreEntrevistaVoz.contieneAlarmaLiteral(turnosLiteralesVoz(chat))
                || !ValidadorCierreEntrevistaVoz.signosAlarmaAfirmados(resultado.signosAlarma()).isEmpty();
        if (alarma && (!resultado.requiereAtencionInmediata() || resultado.nivelPrioridad() < 4)) {
            return "alarm_classification_inconsistent";
        }
        if (java.util.stream.Stream.of(resultado.alergias(), resultado.medicamentos(), resultado.antecedentesRelevantes())
                .flatMap(List::stream).map(this::normalizarTexto)
                .anyMatch(valor -> valor.strip().matches("ningun[oa]?|ningun[oa]?s? conocid[oa]s?|no|no informado|no informada|ningun[oa]? informad[oa]s?"))) {
            return "negation_in_affirmative_list";
        }
        return null;
    }

    private TriageAiResponse corregirClasificacionSiHaceFalta(Chat chat, TriageAiResponse respuesta,
                                                             String datos, long inicio) {
        return corregirClasificacionSiHaceFalta(chat, respuesta, datos, inicio, java.util.Optional.empty(),
                FINAL_SYSTEM_PROMPT);
    }

    private TriageAiResponse corregirClasificacionSiHaceFalta(Chat chat, TriageAiResponse respuesta,
                                                             String datos, long inicio,
                                                             java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico) {
        return corregirClasificacionSiHaceFalta(chat, respuesta, datos, inicio, rechazoSemantico, FINAL_SYSTEM_PROMPT);
    }

    private TriageAiResponse corregirClasificacionSiHaceFalta(Chat chat, TriageAiResponse respuesta,
                                                             String datos, long inicio,
                                                             java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico,
                                                             String promptFinal) {
        return corregirClasificacionSiHaceFalta(chat, respuesta, datos, inicio, rechazoSemantico,
                promptFinal, java.time.Duration.ofSeconds(45));
    }

    private TriageAiResponse corregirClasificacionSiHaceFalta(Chat chat, TriageAiResponse respuesta,
                                                             String datos, long inicio,
                                                             java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico,
                                                             String promptFinal, java.time.Duration presupuesto) {
        String motivo = errorClasificacionFinal(chat, respuesta);
        if (motivo == null && rechazoSemantico.isEmpty()) return respuesta;
        var restante = presupuesto.minusNanos(System.nanoTime() - inicio);
        if (restante.isNegative() || restante.isZero()) throw respuestaIaInvalida(chat, "correction_deadline_exceeded");
        String motivoLog = rechazoSemantico.map(TriageIaClient.MotivoCorreccionFinal::name).orElse(motivo);
        log.info("Chat IA correction chatId={} reason={}", chat.getId(), motivoLog);
        String regla = rechazoSemantico.map(this::reglaCorreccionFinal).orElseGet(() -> switch (motivo) {
            case ALCANCE_CONTRADICTORIO ->
                    "El backend reconoció datos de salud en la entrevista. Conservá los hechos afirmados de los turnos PACIENTE y clasificalos como RELEVANTE, incluso si hay contenido ajeno o ambiguo.";
            case "negation_in_affirmative_list" ->
                    "Las negaciones como Ninguna no son alergias, medicamentos ni antecedentes: usa listas vacias y registra la negacion en observaciones.";
            case "alarm_classification_inconsistent" ->
                    "Tu resultado anterior contenia una alarma o contradecia una alarma literal del paciente. Revisa la evidencia completa. Si hay alarma, requiereAtencionInmediata debe ser true y nivelPrioridad al menos 4. No inventes alarmas ni las elimines para evitar la validacion.";
            default -> "Aplica la rama de cierre que corresponda y devolvé todos los campos requeridos.";
        });
        TriageAiResponse corregida;
        try {
            corregida = triageIaClient.consultarCorreccionFinal(promptFinal
                    + "\nCorreccion solicitada por el backend: " + regla, datos, restante);
        } catch (RuntimeException error) {
            throw error instanceof ProveedorIaException ? error : new ProveedorIaException(error);
        }
        String error = errorClasificacionFinal(chat, corregida);
        if (error != null) throw respuestaIaInvalida(chat, error);
        log.info("Chat IA correction accepted chatId={} reason={}", chat.getId(), motivoLog);
        return corregida;
    }

    private String reglaCorreccionFinal(TriageIaClient.MotivoCorreccionFinal motivo) {
        return switch (motivo) {
            case SIN_RESPALDO -> "Revisa sintomas, signos de alarma, antecedentes, medicamentos y alergias. Conserva un dato solo si un turno del PACIENTE lo afirma claramente; los turnos BOT son contexto, no evidencia. Omite lo que no tenga respaldo.";
            case NEGACION -> "Revisa el alcance de cada negacion. No incluyas como afirmados los sintomas o antecedentes que el paciente nego; conserva la negacion solo en observaciones.";
            case INFERENCIA -> "Quita diagnosticos, causas y sintomas inferidos. Conserva solo lo que el paciente dijo y usa lenguaje descriptivo sin atribuir una causa.";
            case COMUNICACION -> "Corrige el mensaje dirigido al paciente: agradece y confirma el registro, sin mencionar triage, clasificacion, prioridad ni resultados internos.";
            case INTENSIDAD_NO_DOLOR -> "intensidadDolor solo puede contener una escala de dolor que el paciente atribuyo explicitamente al dolor. Una intensidad de otra sensacion no es dolor; usa null si no informo dolor cuantificado.";
            case OMISION_INICIO -> "Conserva en inicio y observaciones cada referencia temporal afirmada por el paciente, incluso si también dice que no sabe la fecha exacta. No inventes una fecha absoluta ni una evolución. Si hay varios inicios, vincula cada uno con su síntoma.";
            case SINTOMAS_AUSENTES -> "Incluye en sintomas al menos un hecho de salud descriptivo expresado por el paciente, también si describe malestar emocional o de salud mental. Conserva sus palabras y evidencia; no inventes sintomas fisicos ni diagnosticos para llenar el campo. Un vacio en el pecho no es dolor toracico salvo que el paciente afirme dolor. Si no hay un hecho respaldado, no lo fabriques ni lo sustituyas por un marcador.";
        };
    }

    /** Explicit retry remains available only after the automatic recovery has ended. */
    @Transactional(noRollbackFor = ProveedorIaException.class)
    public ChatTurnResponse reintentarClasificacion(String idChat, String idPaciente) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
        if (chat.getEstadoClasificacion() != EstadoClasificacionChat.FALLIDA) throw new ChatFinalizadoException();
        return procesarClasificacionCerrada(chat, idPaciente);
    }

    /** Executes exactly the durable claim reserved by the recovery worker. */
    @Transactional
    public ChatTurnResponse procesarClasificacionDurable(String idChat, String idPaciente, int intento) {
        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente)
                .orElseThrow(ChatNoEncontradoException::new);
        if (chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE
                || !chat.isRecuperacionAutomaticaClasificacion() || !chat.isClasificacionAutomaticaEnCurso()
                || chat.getIntentosClasificacionAutomaticos() != intento) throw new ChatFinalizadoException();
        validarConsultaVigente(chat, idPaciente);
        return procesarClasificacionCerrada(chat, idPaciente);
    }

    /** Compatibility entry points use the same worker claim rather than a second retry budget. */
    public ChatTurnResponse reintentarClasificacionAutomaticaTexto(String idChat, String idPaciente) {
        Chat chat = buscarChatPropio(idChat, idPaciente);
        return procesarClasificacionDurable(idChat, idPaciente, chat.getIntentosClasificacionAutomaticos());
    }

    public ChatTurnResponse procesarClasificacionAutomaticaTextoReservada(String idChat, String idPaciente) {
        return reintentarClasificacionAutomaticaTexto(idChat, idPaciente);
    }

    private ChatTurnResponse procesarClasificacionCerrada(Chat chat, String idPaciente) {
        validarConsultaVigente(chat, idPaciente);
        ProgresoEntrevista progreso = progresoEntrevista(chat);
        boolean verificarAlcanceAdministrativo = candidatoCierreSinDatos(progreso);
        long inicio = System.nanoTime();
        iniciarClasificacion(chat);
        try {
            String datos = construirDatosParaClasificacion(chat);
            if (verificarAlcanceAdministrativo) {
                EstadoEntradaChat alcance = consultarAlcanceAdministrativo(datos, inicio, chat);
                if (alcance != EstadoEntradaChat.RELEVANTE) {
                    return cerrarSinHechosClinicos(chat, progreso);
                }
            }
            String promptFinal = promptFinal(progreso, chat.isClasificacionAutomaticaVoz());
            TriageAiResponse respuesta;
            java.util.Optional<TriageIaClient.MotivoCorreccionFinal> rechazoSemantico = java.util.Optional.empty();
            try {
                respuesta = verificarAlcanceAdministrativo
                        ? triageIaClient.consultar(promptFinal, datos, true, plazoRestante(inicio, chat))
                        : chat.isClasificacionAutomaticaVoz()
                            ? triageIaClient.consultar(promptFinal, datos, true, java.time.Duration.ofSeconds(45))
                            : triageIaClient.consultar(promptFinal, datos, true);
            } catch (ProveedorIaException error) {
                rechazoSemantico = TriageIaClient.motivoCorreccionFinal(error);
                if (rechazoSemantico.isEmpty() && !TriageIaClient.esErrorDeRespuesta(error)) throw error;
                respuesta = null;
            }
            respuesta = corregirClasificacionSiHaceFalta(chat, respuesta, datos, inicio, rechazoSemantico, promptFinal);
            if (estadoEntrada(respuesta) != EstadoEntradaChat.RELEVANTE) {
                throw new ProveedorIaException(new IllegalStateException(
                        "La clasificación final contradice el preflight de alcance de la entrevista"));
            }
            String motivoCierre = progreso.motivoCierre() == ProgresoEntrevista.MotivoCierre.EN_CURSO
                    ? "CIERRE_PREVIO" : null;
            return registrarRespuestaBot(chat, idPaciente, respuesta, "GEMINI", progreso, motivoCierre);
        } catch (ProveedorIaException error) {
            if (!chat.isRecuperacionAutomaticaClasificacion()) marcarClasificacionFallida(chat);
            throw error;
        }
    }

    private ProveedorIaException respuestaIaInvalida(Chat chat, String reason) {
        log.warn("Chat IA rejected chatId={} reason={}", chat.getId(), reason);
        return new ProveedorIaException(new IllegalStateException("Gemini devolvio una respuesta de chat no aceptable"), ProveedorIaException.Categoria.VALIDACION_RESPUESTA);
    }

    private ChatTurnResponse registrarRespuestaBot(Chat chat,
                                                   String idPaciente,
                                                   TriageAiResponse respuestaIa,
                                                   String origenRespuesta,
                                                   ProgresoEntrevista progreso,
                                                   String motivoCierreOverride) {
        TriageResultDTO resultadoConPrioridad = respuestaIa.finalizado()
                ? LenguajeClinicoChat.normalizarResultado(respuestaIa.resultado())
                : null;
        String contenidoRespuesta = respuestaIa.mensaje().trim();
        if (resultadoConPrioridad != null && resultadoConPrioridad.requiereAtencionInmediata()
                && resultadoConPrioridad.recomendacionSeguridad() != null
                && !resultadoConPrioridad.recomendacionSeguridad().isBlank()
                && !normalizarTexto(contenidoRespuesta)
                .contains(normalizarTexto(resultadoConPrioridad.recomendacionSeguridad()))) {
            contenidoRespuesta += " " + resultadoConPrioridad.recomendacionSeguridad().trim();
        }
        Mensaje mensajeBot = new Mensaje(contenidoRespuesta, AutorMensaje.BOT, null);
        chat.agregarMensaje(mensajeBot);

        TiempoEstimadoAtencionResponse atencionEstimada = null;
        if (respuestaIa.finalizado()) {
            chat.setFinalizado(true);
            String resultadoJson = escribirResultado(resultadoConPrioridad, origenRespuesta, progreso,
                    motivoCierreOverride);
            chat.setResultadoTriageJson(resultadoJson);
            ConsultaMedica origen = chat.getConsultaOrigenId() == null ? null
                    : bloquearConsulta(chat.getConsultaOrigenId()).orElseThrow(ChatFinalizadoException::new);
            if (origen != null && (origen.getEstadoConsulta() == EstadoConsulta.CANCELADA
                    || origen.getEstadoConsulta() == EstadoConsulta.FINALIZADA)) throw new ChatFinalizadoException();
            if (origen != null && (origen.getEstadoConsulta() == EstadoConsulta.EN_REVISION
                    || origen.getEstadoConsulta() == EstadoConsulta.LLAMADO
                    || origen.getEstadoConsulta() == EstadoConsulta.EN_ATENCION)) {
                // Un resultado tardío completa el caso sin quitar la asignación al médico.
                origen.setChat(chat);
                origen.setResumenPretriageJson(resultadoJson);
                origen.setNivelDeGravedadBot(nivelDeGravedadDesdePrioridad(resultadoConPrioridad.nivelPrioridad()));
                repoConsultasMedicas.save(origen);
            } else {
                try {
                    atencionEstimada = atencionHospitalService.finalizarTriageEIngresarACola(
                            idPaciente, nivelDeGravedadDesdePrioridad(resultadoConPrioridad.nivelPrioridad()),
                            resultadoJson, chat);
                } catch (PacienteSancionadoException rechazo) {
                    // Conserva la recomendación clínica aunque el chat legado no tenga ingreso remoto.
                    mensajeBot.setContenido(contenidoRespuesta + " " + rechazo.getMessage());
                }
            }
            chat.setEstadoClasificacion(EstadoClasificacionChat.COMPLETADA);
            limpiarRecuperacion(chat);
        }

        chat.avanzarVersionEstado();
        repoChat.save(chat);
        return new ChatTurnResponse(
                MapperMensaje.toDTO(mensajeBot),
                atencionEstimada,
                origenRespuesta);
    }

    private RespuestaClasificacionInicial consultarClasificacionFinal(Chat chat, String datos, String promptFinal) {
        try {
            return new RespuestaClasificacionInicial(
                    triageIaClient.consultar(promptFinal, datos, true), java.util.Optional.empty());
        } catch (ProveedorIaException error) {
            var rechazo = TriageIaClient.motivoCorreccionFinal(error);
            if (rechazo.isPresent()) return new RespuestaClasificacionInicial(null, rechazo);
            if (TriageIaClient.esErrorDeRespuesta(error)) return new RespuestaClasificacionInicial(null, java.util.Optional.empty());
            throw error;
        } catch (Exception exception) {
            if (exception instanceof ProveedorIaException proveedorIaException) {
                throw proveedorIaException;
            }
            throw new ProveedorIaException(exception);
        }
    }

    private TriageAiResponse consultarPregunta(Chat chat, long inicioConsulta) {
        String correccion = null;
        java.util.Optional<TriageIaClient.MotivoRechazoSemantico> motivoCorreccion = java.util.Optional.empty();
        ProgresoEntrevista progreso = progresoEntrevista(chat);
        for (int intento = 0; intento < 2; intento++) {
            java.time.Duration restante = java.time.Duration.ofSeconds(45)
                    .minusNanos(System.nanoTime() - inicioConsulta);
            if (restante.isZero() || restante.isNegative()) {
                throw respuestaIaInvalida(chat, "question_deadline_exceeded");
            }
            try {
                TriageAiResponse respuesta = triageIaClient.consultarContinuacion(
                        promptPregunta(progreso, correccion, motivoCorreccion),
                        construirDatosParaPregunta(chat, progreso), restante);
                EstadoEntradaChat estado = estadoEntrada(respuesta);
                boolean contradiceHechos = estado != EstadoEntradaChat.RELEVANTE
                        && progreso.ultimoTurnoPacienteTieneHechos();
                if (estado == EstadoEntradaChat.FUERA_DE_CONTEXTO && !contradiceHechos) return respuesta;
                String error = contradiceHechos ? ALCANCE_CONTRADICTORIO : errorPregunta(chat, respuesta, progreso);
                if (error == null) {
                    if (intento > 0) {
                        log.info("Chat IA question correction accepted chatId={} objectiveType={} reason={} semanticReason={}",
                                chat.getId(), progreso.objetivoDelTurno().tipo().name(),
                                correccion,
                                motivoCorreccion.map(TriageIaClient.MotivoRechazoSemantico::name).orElse("none"));
                    }
                    return respuesta;
                }
                if (intento == 1) throw respuestaIaInvalida(chat, error);
                correccion = error;
                motivoCorreccion = java.util.Optional.empty();
                logCorreccionPregunta(chat, correccion, motivoCorreccion, progreso);
            } catch (ProveedorIaException error) {
                if (intento == 1 || !TriageIaClient.esErrorDeRespuesta(error)) throw error;
                motivoCorreccion = TriageIaClient.motivoRechazo(error);
                correccion = motivoCorreccion.isPresent()
                        ? "question_semantic_verification" : "invalid_question_response";
                logCorreccionPregunta(chat, correccion, motivoCorreccion, progreso);
            } catch (RuntimeException error) {
                throw new ProveedorIaException(error);
            }
        }
        throw respuestaIaInvalida(chat, "invalid_question_response");
    }

    private void logCorreccionPregunta(Chat chat, String razonTecnica,
                                       java.util.Optional<TriageIaClient.MotivoRechazoSemantico> motivoSemantico,
                                       ProgresoEntrevista progreso) {
        log.info("Chat IA correction chatId={} objectiveType={} reason={} semanticReason={}", chat.getId(),
                progreso.objetivoDelTurno().tipo().name(), razonTecnica,
                motivoSemantico.map(TriageIaClient.MotivoRechazoSemantico::name).orElse("none"));
    }

    private String promptPregunta(ProgresoEntrevista progreso, String correccion,
                                  java.util.Optional<TriageIaClient.MotivoRechazoSemantico> motivoSemantico) {
        String tarea = progreso.instruccionSiguientePaso();
        String correccionTexto = motivoSemantico.map(motivo -> " La pregunta anterior no fue aceptable. "
                + reglaCorreccionPregunta(motivo, progreso)
                + " Reescribila como una unica pregunta breve, pertinente al relato, sin repetir la pregunta anterior.")
                .orElseGet(() -> {
                    if (correccion == null) return "";
                    if (ALCANCE_CONTRADICTORIO.equals(correccion)) {
                        return " El backend reconoció un hecho de salud o una respuesta pertinente en el último turno. "
                                + "Usá RELEVANTE y respetá objetivoDelTurno aunque ese turno incluya contenido ajeno o ambiguo.";
                    }
                    String reglaLocal = reglaCorreccionObjetivoPregunta(progreso, correccion);
                    if (reglaLocal != null) {
                        return " La pregunta anterior no respetó el objetivo del turno. " + reglaLocal
                                + " Reescribila como una única pregunta breve y pertinente, sin repetir la pregunta anterior.";
                    }
                    return " La respuesta anterior no fue aceptable (" + correccion + "). Reescribila como una "
                            + "unica pregunta breve, pertinente al relato, sin repetir la pregunta anterior.";
                });
        return "Sos un asistente de admision para pre-triage. " + tarea + correccionTexto
                + "\n"
                + " Usa espanol claro y no diagnostiques ni inventes datos. "
                + "Una respuesta de que no tiene otros sintomas no impide cuantificar los sintomas ya afirmados. "
                + "Si dice que no sabe o no quiere responder, conserva ese dato como desconocido y no insistas. "
                + LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO;
    }

    private String reglaCorreccionObjetivoPregunta(ProgresoEntrevista progreso, String errorLocal) {
        ProgresoEntrevista.ObjetivoTurno objetivo = progreso.objetivoDelTurno();
        if (objetivo.dimension() == ProgresoEntrevista.Dimension.INTENSIDAD) {
            return switch (errorLocal) {
                case "ESCALA_DOLOR_AUSENTE", "DIMENSION_NO_COINCIDE" ->
                        "Preguntá por la intensidad del dolor pendiente e incluí explícitamente la escala del 1 al 10.";
                default -> null;
            };
        }
        if (objetivo.tipo() != ProgresoEntrevista.TipoObjetivo.ACLARACION || objetivo.sintomaId() != null
                || objetivo.dimension() != null) {
            return null;
        }
        return switch (errorLocal) {
            case "ACLARACION_FUERA_DE_OBJETIVO", "SINTOMA_NO_COINCIDE", "DIMENSION_NO_COINCIDE" ->
                    reglaAclaracionInicial();
            default -> null;
        };
    }

    private String reglaCorreccionPregunta(TriageIaClient.MotivoRechazoSemantico motivo,
                                           ProgresoEntrevista progreso) {
        boolean aclaracionInicial = progreso.objetivoDelTurno().tipo() == ProgresoEntrevista.TipoObjetivo.ACLARACION
                && progreso.objetivoDelTurno().sintomaId() == null
                && progreso.objetivoDelTurno().dimension() == null;
        if (aclaracionInicial) {
            String alcance = reglaAclaracionInicial();
            return switch (motivo) {
                case COMUNICACION -> "Formula una sola pregunta breve para continuar y no menciones registro, "
                        + "despedida, clasificación, triage ni resultados. " + alcance;
                case NEGACION -> "Respeta la negación expresada por la persona. " + alcance;
                case INFERENCIA, SIN_RESPALDO, INTENSIDAD_NO_DOLOR, OMISION_INICIO -> alcance;
            };
        }
        return switch (motivo) {
            case COMUNICACION -> progreso.objetivoDelTurno().tipo() == ProgresoEntrevista.TipoObjetivo.INVITACION
                    ? "El objetivo sigue siendo una invitación abierta. Pregunta en una sola invitación si quiere "
                            + "agregar algo sobre lo que ya contó; no pidas un dato ni sugieras síntomas nuevos. "
                            + "No resumas ni reinterpretes su contexto como causa o diagnóstico, y no cierres ni "
                            + "anuncies el registro. Evita empatía redundante; si aporta, una frase muy breve de "
                            + "reconocimiento neutral puede preceder inmediatamente a esa única invitación."
                    : "Formula una sola pregunta breve para continuar. No menciones registro, despedida, clasificación, triage ni resultados.";
            case INFERENCIA -> "Pregunta neutralmente solo por el dato pendiente; no asumas causas ni hechos que la persona no informó.";
            case NEGACION -> "Respeta la negación que ya dio la persona. No la reformules como afirmación y pregunta solo el dato pendiente.";
            case SIN_RESPALDO -> "No presentes como hecho ningún dato nuevo. Pregunta solo por la dimensión pendiente, sin atribuir síntomas no informados.";
            case INTENSIDAD_NO_DOLOR -> "No asocies al dolor la intensidad de una sensación no dolorosa. Pregunta solo por la sensación ya mencionada y la dimensión pendiente.";
            case OMISION_INICIO -> "Conserva el inicio que el paciente ya afirmó y pregunta únicamente por el dato pendiente, sin inferir una fecha.";
        };
    }

    private String reglaAclaracionInicial() {
        return "Invita a la persona a explicar con sus propias palabras qué quiso decir o qué siente, "
                + "retomando de forma neutral la expresión que usó. No la categorices ni le ofrezcas opciones "
                + "físicas o emocionales; tampoco preguntes por dolor, duración, intensidad, causas o síntomas "
                + "nuevos todavía.";
    }

    private String errorPregunta(Chat chat, TriageAiResponse respuesta, ProgresoEntrevista progreso) {
        if (respuesta == null || respuesta.finalizado() || respuesta.resultado() != null
                || respuesta.mensaje() == null || respuesta.mensaje().isBlank()) return "invalid_question_response";
        Optional<String> errorObjetivo = progreso.validarPreguntaPropuesta(respuesta.mensaje());
        if (errorObjetivo.isPresent()) return errorObjetivo.get();
        // Punctuation is a style concern, not a failed patient turn. Never discard a
        // valid provider message solely for missing, repeated or Unicode question marks.
        if (repitePregunta(chat, respuesta)) return "repeated_question";
        return null;
    }

    private String construirDatosParaClasificacion(Chat chat) {
        return construirDatosParaClasificacion(turnosLiteralesVoz(chat));
    }

    private String construirDatosParaClasificacion(List<TurnoVoz> turnos) {
        return construirEntradaEstructurada(turnos, null);
    }

    private void agregarTurnosVoz(Chat chat, List<TurnoVoz> turnos) {
        for (TurnoVoz turno : turnos == null ? List.<TurnoVoz>of() : turnos) {
            if (turno.contenido() == null || turno.contenido().isBlank()) {
                continue;
            }
            boolean esPaciente = turno.autor() == AutorMensaje.PACIENTE;
            chat.agregarMensaje(new Mensaje(turno.contenido().trim(), turno.autor(),
                    esPaciente ? chat.getPaciente() : null));
        }
    }

    private String construirDatosVozParaClasificacion(List<TurnoVoz> turnos, ResumenEntrevistaVoz resumen) {
        return construirEntradaEstructurada(turnos, resumen);
    }

    private String construirEntradaEstructurada(List<TurnoVoz> turnos, ResumenEntrevistaVoz resumen) {
        ObjectNode entrada = objectMapper.createObjectNode();
        var mensajes = entrada.putArray("turnos");
        for (TurnoVoz turno : turnos) {
            if (turno == null || turno.autor() == null) continue;
            ObjectNode mensaje = mensajes.addObject();
            mensaje.put("autor", turno.autor().name());
            mensaje.put("contenido", turno.contenido() == null ? "" : turno.contenido());
        }
        if (resumen != null) {
            entrada.set("resumenNoVerificado", objectMapper.valueToTree(resumen));
        }
        try {
            return objectMapper.writeValueAsString(entrada);
        } catch (JacksonException error) {
            throw new IllegalStateException("No se pudo preparar la entrada estructurada del chat", error);
        }
    }

    private List<TurnoVoz> turnosLiteralesVoz(Chat chat) {
        return mensajesLiteralesVoz(chat).stream()
                .map(mensaje -> new TurnoVoz(mensaje.getAutor(), mensaje.getContenido()))
                .toList();
    }

    /** Excluded patient turns remain in local history; a BOT quote of one cannot re-enter triage input. */
    private List<Mensaje> mensajesLiteralesVoz(Chat chat) {
        List<Mensaje> mensajes = new ArrayList<>();
        boolean omitirBotHastaPacienteAdmitido = false;
        for (Mensaje mensaje : chat.getMensajes()) {
            if (mensaje.getAutor() == AutorMensaje.PACIENTE) {
                if (Boolean.TRUE.equals(mensaje.getExcluidoDelTriage())) {
                    // A BOT response may quote an excluded patient message. Keep it in stored history
                    // for conversation/progress accounting, but do not let it re-enter provider input.
                    omitirBotHastaPacienteAdmitido = true;
                    continue;
                }
                mensajes.add(mensaje);
                omitirBotHastaPacienteAdmitido = false;
            } else if (!omitirBotHastaPacienteAdmitido) {
                mensajes.add(mensaje);
            }
        }
        return List.copyOf(mensajes);
    }

    private String construirConversacion(Chat chat) {
        return construirDatosParaClasificacion(turnosLiteralesVoz(chat));
    }

    /** The question path intentionally receives only clinical, non-excluded turns. */
    private String construirDatosParaPregunta(Chat chat, ProgresoEntrevista progreso) {
        try {
            ObjectNode entrada = (ObjectNode) objectMapper.readTree(
                    construirDatosParaClasificacion(turnosLiteralesVoz(chat)));
            entrada.set("objetivoDelTurno", objectMapper.valueToTree(progreso.objetivoDelTurno()));
            return objectMapper.writeValueAsString(entrada);
        } catch (JacksonException error) {
            throw new IllegalStateException("No se pudo preparar el objetivo seguro de la pregunta", error);
        }
    }

    private ProgresoEntrevista progresoEntrevista(Chat chat) {
        List<ProgresoEntrevista.Turno> historial = chat.getMensajes().stream()
                .map(m -> new ProgresoEntrevista.Turno(m.getAutor(), m.getContenido(),
                        Boolean.TRUE.equals(m.getExcluidoDelTriage())))
                .toList();
        return ProgresoEntrevista.evaluarConContexto(historial);
    }

    private ProgresoEntrevista progresoEntrevista(Chat chat, List<TurnoVoz> adicionales) {
        return ProgresoEntrevista.evaluarConContexto(turnosParaProgresoVoz(chat, adicionales));
    }

    private List<ProgresoEntrevista.Turno> turnosParaProgresoVoz(Chat chat, List<TurnoVoz> adicionales) {
        List<ProgresoEntrevista.Turno> historial = new ArrayList<>();
        for (Mensaje mensaje : chat.getMensajes()) {
            historial.add(new ProgresoEntrevista.Turno(mensaje.getAutor(), mensaje.getContenido(),
                    Boolean.TRUE.equals(mensaje.getExcluidoDelTriage())));
        }
        if (adicionales != null) {
            for (TurnoVoz turno : adicionales) {
                if (turno != null) historial.add(new ProgresoEntrevista.Turno(turno.autor(), turno.contenido(), false));
            }
        }
        return historial;
    }

    private boolean permiteCierreAdministrativo(ProgresoEntrevista progreso) {
        return switch (progreso.motivoCierre()) {
            case LIMITE_RECURSOS, SIN_AVANCE, SOLICITUD_PACIENTE -> true;
            default -> false;
        };
    }

    private boolean candidatoCierreSinDatos(ProgresoEntrevista progreso) {
        return permiteCierreAdministrativo(progreso) && !progreso.tieneHechosClinicos();
    }

    private java.time.Duration plazoRestante(long inicio, Chat chat) {
        java.time.Duration restante = java.time.Duration.ofSeconds(45)
                .minusNanos(System.nanoTime() - inicio);
        if (restante.isZero() || restante.isNegative()) {
            throw respuestaIaInvalida(chat, "closure_deadline_exceeded");
        }
        return restante;
    }

    private java.time.Duration plazoRestante(long inicio, Chat chat, java.time.Duration presupuesto) {
        java.time.Duration restante = plazoRestante(inicio, chat);
        return restante.compareTo(presupuesto) <= 0 ? restante : presupuesto;
    }

    private EstadoEntradaChat consultarAlcanceAdministrativo(String datos, long inicio, Chat chat) {
        EstadoEntradaChat alcance = triageIaClient.consultarAlcance(datos, plazoRestante(inicio, chat));
        if (alcance == null) {
            throw new ProveedorIaException(new IllegalStateException("Gemini no devolvió un estado de alcance válido"));
        }
        return alcance;
    }

    private boolean hayTurnoPacienteIncluido(Chat chat) {
        return chat.getMensajes().stream()
                .anyMatch(m -> m.getAutor() == AutorMensaje.PACIENTE
                        && !Boolean.TRUE.equals(m.getExcluidoDelTriage()));
    }

    private boolean requiereAclaracionDeDato(ProgresoEntrevista progreso) {
        return progreso.requiereAclaracion() && progreso.datos().stream()
                .anyMatch(dato -> dato.dimension() == ProgresoEntrevista.Dimension.TEMPERATURA
                        && dato.estado() == ProgresoEntrevista.Estado.PENDIENTE && dato.intentos() > 0);
    }

    private boolean esRespuestaPuraConUnidadIncompatible(String contenido) {
        String normalizado = normalizarTexto(contenido).strip();
        return normalizado.matches("\\d{2,3}(?:[.,]\\d+)?\\s*(?:kg|kilogramos?|cm|mm|metros?|libras?)(?:\\s*(?:celsius|grados?))?[.!]?");
    }

    private String promptFinal(ProgresoEntrevista progreso, boolean voz) {
        String base = voz ? FINAL_SYSTEM_PROMPT_VOZ : FINAL_SYSTEM_PROMPT;
        return base + "\nEstado de cierre de entrevista: " + motivoCierrePersistido(progreso) + ". "
                + "Los datos pendientes, desconocidos, rechazados o agotados siguen sin informar.";
    }

    private String motivoCierrePersistido(ProgresoEntrevista progreso) {
        return progreso.motivoCierre() == ProgresoEntrevista.MotivoCierre.EN_CURSO
                ? "CIERRE_PREVIO" : progreso.motivoCierre().name();
    }

    private boolean esResultadoFinalValido(TriageResultDTO resultado) {
        if (resultado == null || resultado.nivelPrioridad() == null
                || resultado.nivelPrioridad() < 1 || resultado.nivelPrioridad() > 5) {
            return false;
        }
        return resultado.motivoConsulta() != null && !resultado.motivoConsulta().isBlank()
                && resultado.sintomas() != null
                && resultado.signosAlarma() != null
                && resultado.antecedentesRelevantes() != null
                && resultado.medicamentos() != null
                && resultado.alergias() != null
                && resultado.inicio() != null
                && resultado.evolucion() != null
                && resultado.posibilidadEmbarazo() != null
                && resultado.observaciones() != null
                && resultado.recomendacionSeguridad() != null
                && !resultado.recomendacionSeguridad().isBlank();
    }

    private boolean puedeAceptarCierreIa(Chat chat, TriageAiResponse respuestaIa,
                                         ProgresoEntrevista progreso) {
        if (errorClasificacionFinal(chat, respuestaIa) != null) {
            return false;
        }
        return progreso.puedeCerrar();
    }

    private boolean repitePregunta(Chat chat, TriageAiResponse respuestaIa) {
        if (respuestaIa == null || respuestaIa.finalizado() || respuestaIa.mensaje() == null) {
            return false;
        }

        String respuestaNormalizada = normalizarPregunta(respuestaIa.mensaje());
        List<String> preguntasPrevias = chat.getMensajes().stream()
                .filter(mensaje -> mensaje.getAutor() == AutorMensaje.BOT)
                .map(Mensaje::getContenido)
                .map(this::normalizarPregunta)
                .toList();
        if (preguntasPrevias.isEmpty()) return false;
        String ultimaPregunta = preguntasPrevias.get(preguntasPrevias.size() - 1);
        if (respuestaNormalizada.equals(ultimaPregunta)) {
            return !puedeRepetirPreguntaPendiente(chat, ultimaPregunta);
        }
        return preguntasPrevias.stream().anyMatch(respuestaNormalizada::equals);
    }

    private boolean puedeRepetirPreguntaPendiente(Chat chat, String ultimaPregunta) {
        ProgresoEntrevista progreso = progresoEntrevista(chat);
        return progreso.requiereAclaracion() && progreso.datos().stream()
                .anyMatch(dato -> dato.estado() == ProgresoEntrevista.Estado.PENDIENTE
                        && dato.intentos() > 0
                        && dato.intentos() < ProgresoEntrevista.MAX_INTENTOS_POR_DATO
                        && preguntaCorrespondeAlDato(ultimaPregunta, dato.dimension()));
    }

    private boolean preguntaCorrespondeAlDato(String pregunta, ProgresoEntrevista.Dimension dimension) {
        return switch (dimension) {
            case INICIO -> pregunta.contains("desde cuando") || pregunta.contains("cuando empezo")
                    || pregunta.contains("cuando comenzo") || pregunta.contains("hace cuanto");
            case INTENSIDAD -> pregunta.contains("intensidad") || pregunta.contains("cuanto te duele")
                    || pregunta.contains("0 al 10") || pregunta.contains("1 al 10") || pregunta.contains("escala");
            case IMPACTO -> pregunta.contains("impacto") || pregunta.contains("afecta") || pregunta.contains("limita")
                    || pregunta.contains("te impide") || pregunta.contains("actividades") || pregunta.contains("hacer tus cosas")
                    || pregunta.contains("0 al 10") || pregunta.contains("1 al 10") || pregunta.contains("escala");
            case TEMPERATURA -> pregunta.contains("temperatura") || pregunta.contains("grados");
        };
    }

    private String normalizarPregunta(String texto) {
        return normalizarTexto(texto).replaceAll("[¿?¡!.,;:]+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private NivelDeGravedad nivelDeGravedadDesdePrioridad(Integer nivelPrioridad) {
        return switch (nivelPrioridad) {
            case 5 -> NivelDeGravedad.RIESGO_VITAL_INMEDIATO;
            case 4 -> NivelDeGravedad.MUY_URGENTE;
            case 3 -> NivelDeGravedad.URGENTE;
            case 2 -> NivelDeGravedad.NORMAL;
            default -> NivelDeGravedad.NO_URGENTE;
        };
    }

    private String normalizarTexto(String texto) {
        return texto == null ? "" : texto.toLowerCase(Locale.ROOT)
                .replace('á', 'a')
                .replace('é', 'e')
                .replace('í', 'i')
                .replace('ó', 'o')
                .replace('ú', 'u')
                .replace('ñ', 'n');
    }

    private Chat buscarChatPropio(String idChat, String idPaciente) {
        try {
            return repoChat.findByIdAndPacienteUsuarioAuthId(Long.valueOf(idChat), idPaciente)
                    .orElseThrow(ChatNoEncontradoException::new);
        } catch (NumberFormatException exception) {
            throw new ChatNoEncontradoException();
        }
    }

    private String escribirResultado(TriageResultDTO resultado, String origenClasificacion,
                                    ProgresoEntrevista progreso, String motivoCierreOverride) {
        if (resultado == null) {
            throw new ProveedorIaException(new IllegalStateException("La IA finalizo sin resultado estructurado"));
        }
        try {
            ObjectNode resultadoConOrigen = objectMapper.valueToTree(resultado);
            resultadoConOrigen.put("origenClasificacion", origenClasificacion);
            ObjectNode seguimiento = resultadoConOrigen.putObject("seguimientoEntrevista");
            seguimiento.put("motivoCierre", motivoCierreOverride == null
                    ? motivoCierrePersistido(progreso) : motivoCierreOverride);
            var datos = seguimiento.putArray("datos");
            for (ProgresoEntrevista.Dato dato : progreso.datos()) {
                ObjectNode resumen = datos.addObject();
                resumen.put("sintomaId", dato.sintomaId());
                resumen.put("dimension", dato.dimension().name());
                resumen.put("estado", dato.estado().name());
                resumen.put("intentos", dato.intentos());
            }
            return objectMapper.writeValueAsString(resultadoConOrigen);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo guardar el resultado del triage", exception);
        }
    }

    private TriageResultDTO leerResultado(Chat chat) {
        if (chat.getResultadoTriageJson() == null || chat.getResultadoTriageJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(chat.getResultadoTriageJson(), TriageResultDTO.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo leer el resultado del triage", exception);
        }
    }
}

