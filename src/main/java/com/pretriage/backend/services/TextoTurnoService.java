package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.exceptions.ChatFinalizadoException;
import com.pretriage.backend.exceptions.ChatNoEncontradoException;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.mappers.MapperChat;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.repositories.RepoChat;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable, bounded worker for text interview turns. */
@Service
public class TextoTurnoService {
    private static final Logger log = LoggerFactory.getLogger(TextoTurnoService.class);
    private static final int MAX_INTENTOS = 2;
    private final RepoChat repoChat;
    private final ChatService chatService;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNuevo;

    public TextoTurnoService(RepoChat repoChat, ChatService chatService,
                             PlatformTransactionManager transactionManager) {
        this.repoChat = repoChat;
        this.chatService = chatService;
        this.tx = new TransactionTemplate(transactionManager);
        this.txNuevo = new TransactionTemplate(transactionManager);
        this.txNuevo.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(2);
        this.executor.setMaxPoolSize(2);
        this.executor.setQueueCapacity(64);
        this.executor.setThreadNamePrefix("text-turn-");
        this.executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        this.executor.initialize();
    }

    /** Atomically stores the patient turn and its idempotency key before returning 202. */
    public ChatDTO aceptarTurno(String idChat, String idPaciente, String contenido, String solicitudId) {
        String requestId = solicitudId == null || solicitudId.isBlank()
                ? UUID.randomUUID().toString() : UUID.fromString(solicitudId).toString();
        String texto = contenido.trim();
        Long chatId = Long.valueOf(idChat);
        RepoChat.TextTurnSnapshot snapshot = repoChat.findTextTurnSnapshot(chatId, idPaciente)
                .orElseThrow(ChatNoEncontradoException::new);
        var replay = repoChat.findTextTurnContent(chatId, requestId);
        if (replay.isPresent()) {
            if (!texto.equals(replay.get())) throw conflicto("El identificador ya se usó con otro mensaje.");
            return chatService.obtenerChat(idChat, idPaciente);
        }
        EstadoTurnoTexto estado = snapshot.getEstadoTurnoTexto() == null
                ? EstadoTurnoTexto.SIN_SOLICITAR : snapshot.getEstadoTurnoTexto();
        if (estado == EstadoTurnoTexto.PENDIENTE) {
            throw conflicto("Ya hay un mensaje en proceso.");
        }
        if (estado == EstadoTurnoTexto.NO_DISPONIBLE) {
            throw conflicto("El asistente no está disponible para continuar esta entrevista.");
        }
        AtomicBoolean nuevo = new AtomicBoolean(false);
        ChatDTO result = tx.execute(status -> {
            Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente)
                    .orElseThrow(ChatNoEncontradoException::new);
            Mensaje previo = encontrarSolicitud(chat, requestId);
            if (previo != null) {
                if (!texto.equals(previo.getContenido())) throw conflicto("El identificador ya se usó con otro mensaje.");
                return MapperChat.toDTO(chat);
            }
            if (chat.getEstadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE) {
                throw conflicto("Ya hay un mensaje en proceso.");
            }
            if (chat.getEstadoTurnoTexto() == EstadoTurnoTexto.NO_DISPONIBLE) {
                throw conflicto("El asistente no está disponible para continuar esta entrevista.");
            }
            if (chat.isFinalizado()) throw new ChatFinalizadoException();
            Mensaje mensaje = new Mensaje(texto, AutorMensaje.PACIENTE, chat.getPaciente());
            mensaje.setSolicitudId(requestId);
            chat.agregarMensaje(mensaje);
            chat.setSolicitudTurnoTextoId(requestId);
            chat.setIntentosTurnoTexto(0);
            chat.setTurnoTextoEnCurso(false);
            chat.setErrorTurnoTexto(null);
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.PENDIENTE);
            repoChat.save(chat);
            nuevo.set(true);
            return MapperChat.toDTO(chat);
        });
        if (nuevo.get() && result.estadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE) {
            try { executor.execute(() -> procesar(idChat, idPaciente, requestId)); }
            catch (RuntimeException ignored) { /* ApplicationReady recovery will pick up committed work. */ }
        }
        return result;
    }

    /** Recover only turns explicitly owned by this service; never replay the patient submission. */
    @EventListener(ApplicationReadyEvent.class)
    public void recuperarPendientesAlIniciar() {
        encolarPendientes();
    }

    @Scheduled(fixedDelay = 30000)
    public void recuperarPendientesPeriodicamente() {
        encolarPendientes();
    }

    private void encolarPendientes() {
        for (Object[] fila : repoChat.findTextTurnRecoveryCandidates(EstadoTurnoTexto.PENDIENTE,
                LocalDateTime.now().minusSeconds(150))) {
            String idChat = String.valueOf(fila[0]);
            String idPaciente = String.valueOf(fila[1]);
            String requestId = String.valueOf(fila[2]);
            try { executor.execute(() -> procesar(idChat, idPaciente, requestId)); }
            catch (RuntimeException ignored) { /* Durable PENDIENTE is reconsidered on the next scheduled scan. */ }
        }
    }

    @PreDestroy
    public void apagarExecutor() { executor.shutdown(); }

    private void procesar(String idChat, String idPaciente, String requestId) {
        try {
            procesarConReintentos(idChat, idPaciente, requestId);
        } catch (RuntimeException error) {
            try { marcarNoDisponible(idChat, idPaciente, requestId, null, "error_operativo"); }
            catch (RuntimeException ignored) { /* The periodic recovery scan can retry if persistence is unavailable. */ }
        }
    }

    private void procesarConReintentos(String idChat, String idPaciente, String requestId) {
        while (true) {
            Integer intento = txNuevo.execute(status -> {
                Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente)
                        .orElseThrow(ChatNoEncontradoException::new);
                if (chat.getEstadoTurnoTexto() != EstadoTurnoTexto.PENDIENTE
                        || !requestId.equals(chat.getSolicitudTurnoTextoId())) return 0;
                if (chat.isTurnoTextoEnCurso() && chat.getTurnoTextoIniciadoEn() != null
                        && chat.getTurnoTextoIniciadoEn().isAfter(LocalDateTime.now().minusSeconds(150))) return 0;
                if (chat.getIntentosTurnoTexto() >= MAX_INTENTOS) return -1;
                chat.setIntentosTurnoTexto(chat.getIntentosTurnoTexto() + 1);
                chat.setTurnoTextoEnCurso(true);
                chat.setTurnoTextoIniciadoEn(LocalDateTime.now());
                repoChat.save(chat);
                return chat.getIntentosTurnoTexto();
            });
            if (intento == null || intento == 0) return;
            if (intento < 0) { marcarNoDisponible(idChat, idPaciente, requestId, MAX_INTENTOS, "proveedor_no_disponible"); return; }
            long startedAt = System.nanoTime();
            try {
                chatService.procesarTurnoTextoPersistido(idChat, idPaciente, requestId, intento);
                log.info("Text turn completed chatId={} requestId={} attempt={} elapsedMs={}", idChat,
                        requestId, intento, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            } catch (RuntimeException error) {
                boolean transientProvider = error instanceof ProveedorIaException proveedor && proveedor.esTransitorio();
                if (transientProvider && intento < MAX_INTENTOS) {
                    txNuevo.executeWithoutResult(status -> {
                        Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
                        if (chat.getEstadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE
                                && requestId.equals(chat.getSolicitudTurnoTextoId())
                                && chat.getIntentosTurnoTexto() == intento) {
                            chat.setTurnoTextoEnCurso(false);
                            chat.setTurnoTextoIniciadoEn(null);
                            repoChat.save(chat);
                        }
                    });
                    log.warn("Text turn attempt failed chatId={} requestId={} attempt={} category={} elapsedMs={}",
                            idChat, requestId, intento, "transient_provider",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                    continue;
                }
                String category = transientProvider ? "proveedor_no_disponible" : "respuesta_no_valida";
                marcarNoDisponible(idChat, idPaciente, requestId, intento, category);
                log.warn("Text turn stopped chatId={} requestId={} attempt={} category={} elapsedMs={}",
                        idChat, requestId, intento, category,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            }
        }
    }

    private void marcarNoDisponible(String idChat, String idPaciente, String requestId, Integer intento,
                                    String categoria) {
        txNuevo.executeWithoutResult(status -> {
            Chat chat = repoChat.findForUpdate(Long.valueOf(idChat), idPaciente).orElseThrow(ChatNoEncontradoException::new);
            if (chat.getEstadoTurnoTexto() != EstadoTurnoTexto.PENDIENTE
                    || !requestId.equals(chat.getSolicitudTurnoTextoId())
                    || (intento != null && chat.getIntentosTurnoTexto() != intento)
                    || (intento == null && chat.isTurnoTextoEnCurso())) return;
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.NO_DISPONIBLE);
            chat.setTurnoTextoEnCurso(false);
            chat.setTurnoTextoIniciadoEn(null);
            chat.setErrorTurnoTexto(categoria);
            // Preserve the terminal failed-classification state without creating a triage result.
            if (chat.getEstadoClasificacion() == EstadoClasificacionChat.FALLIDA
                    || chat.getEstadoClasificacion() == EstadoClasificacionChat.PENDIENTE) {
                chat.setFinalizado(true);
                chat.setEstadoClasificacion(EstadoClasificacionChat.FALLIDA);
                chat.setResultadoTriageJson(null);
            }
            chat.setRecuperacionAutomaticaClasificacion(false);
            chat.setClasificacionAutomaticaEnCurso(false);
            repoChat.save(chat);
        });
    }

    private static ConflictoDeEstadoException conflicto(String mensaje) {
        return new ConflictoDeEstadoException(mensaje);
    }

    private static Mensaje encontrarSolicitud(Chat chat, String requestId) {
        return chat.getMensajes().stream().filter(m -> requestId.equals(m.getSolicitudId())).findFirst().orElse(null);
    }

}
