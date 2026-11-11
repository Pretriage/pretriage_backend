package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.exceptions.*;
import com.pretriage.backend.mappers.MapperChat;
import com.pretriage.backend.model.chat.*;
import com.pretriage.backend.repositories.RepoChat;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable jobs for accepted text turns and closed text/voice classifications. */
@Service
public class TextoTurnoService {
    private static final Logger log = LoggerFactory.getLogger(TextoTurnoService.class);
    private static final int MAX_FALLOS_INTERNOS = 2;
    private final RepoChat repoChat;
    private final ChatService chatService;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;
    private final Set<String> encolados = ConcurrentHashMap.newKeySet();
    @Value("${chat.recuperacion.legacy-activos:true}") private boolean recuperarLegacy = true;
    @Value("${chat.recuperacion.texto-demora-ms:2000}") private long demoraTextoMs = 2000;
    @Value("${chat.recuperacion.clasificacion-demora-ms:5000}") private long demoraClasificacionMs = 5000;
    @Value("${chat.recuperacion.api-demora-inicial-ms:30000}") private long demoraApiMs = 30000;

    public TextoTurnoService(RepoChat repoChat, ChatService chatService, PlatformTransactionManager manager) {
        this.repoChat = repoChat;
        this.chatService = chatService;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-recovery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
    }

    /** The patient message and key are committed before returning 202 or starting any AI call. */
    public ChatDTO aceptarTurno(String idChat, String idPaciente, String contenido, String solicitudId) {
        String requestId = solicitudId == null || solicitudId.isBlank()
                ? UUID.randomUUID().toString() : UUID.fromString(solicitudId).toString();
        String texto = contenido.trim();
        long chatId = Long.parseLong(idChat);
        var snapshot = repoChat.findTextTurnSnapshot(chatId, idPaciente).orElseThrow(ChatNoEncontradoException::new);
        var replay = repoChat.findTextTurnContent(chatId, requestId);
        if (replay.isPresent()) {
            if (!texto.equals(replay.get())) throw conflicto("El identificador ya se usó con otro mensaje.");
            return chatService.obtenerChat(idChat, idPaciente);
        }
        // Do not wait behind the AI transaction just to reject a second submission.
        // The locked checks below still fence a concurrent first submission.
        if (snapshot.getFinalizado()) throw new ChatFinalizadoException();
        if (snapshot.getEstadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE
                || snapshot.getEstadoTurnoTexto() == EstadoTurnoTexto.NO_DISPONIBLE) throw conflicto("Ya hay un mensaje en proceso.");
        AtomicBoolean nuevo = new AtomicBoolean(false);
        ChatDTO result = tx.execute(status -> {
            Chat chat = propio(idChat, idPaciente);
            Mensaje previo = chat.getMensajes().stream().filter(m -> requestId.equals(m.getSolicitudId())).findFirst().orElse(null);
            if (previo != null) {
                if (!texto.equals(previo.getContenido())) throw conflicto("El identificador ya se usó con otro mensaje.");
                return MapperChat.toDTO(chat);
            }
            if (chat.isFinalizado()) throw new ChatFinalizadoException();
            chatService.validarAtencionNoIniciada(chat);
            if (chat.getEstadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE
                    || chat.getEstadoTurnoTexto() == EstadoTurnoTexto.NO_DISPONIBLE) throw conflicto("Ya hay un mensaje en proceso.");
            Mensaje mensaje = new Mensaje(texto, AutorMensaje.PACIENTE, chat.getPaciente());
            mensaje.setSolicitudId(requestId);
            chat.agregarMensaje(mensaje);
            chat.setSolicitudTurnoTextoId(requestId);
            chat.setIntentosTurnoTexto(0);
            chat.setFallosInternosTurnoTexto(0);
            chat.setFallosDisponibilidad(0);
            chat.setTurnoTextoEnCurso(false);
            chat.setErrorTurnoTexto(null);
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.PENDIENTE);
            chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
            chat.setProximoReintentoEn(LocalDateTime.now());
            chat.avanzarVersionEstado();
            repoChat.save(chat);
            nuevo.set(true);
            return MapperChat.toDTO(chat);
        });
        if (nuevo.get()) encolar(idChat, () -> procesarTexto(idChat, idPaciente, requestId));
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recuperarPendientesAlIniciar() { recuperarPendientesPeriodicamente(); }

    @Scheduled(fixedDelayString = "${chat.recuperacion.scan-ms:1000}")
    public void recuperarPendientesPeriodicamente() {
        if (recuperarLegacy) {
            for (Object[] fila : repoChat.findLegacyTextRecoveryCandidates()) {
                try { chatService.recuperarTurnoLegacy(String.valueOf(fila[0]), String.valueOf(fila[1])); }
                catch (ChatFinalizadoException ignored) { /* Old consultation: no replay. */ }
                catch (RuntimeException error) { log.warn("Legacy chat recovery deferred chatId={} errorType={}", fila[0], error.getClass().getSimpleName()); }
            }
        }
        for (Object[] fila : repoChat.findTextTurnRecoveryCandidates(EstadoTurnoTexto.PENDIENTE, LocalDateTime.now().minusSeconds(150), LocalDateTime.now())) {
            String id = String.valueOf(fila[0]), paciente = String.valueOf(fila[1]), solicitud = String.valueOf(fila[2]);
            encolar(id, () -> procesarTexto(id, paciente, solicitud));
        }
        for (Object[] fila : repoChat.findAutomaticClassificationRecoveryCandidates(EstadoClasificacionChat.PENDIENTE, LocalDateTime.now(), LocalDateTime.now().minusSeconds(150))) {
            String id = String.valueOf(fila[0]), paciente = String.valueOf(fila[1]);
            encolar(id, () -> procesarClasificacion(id, paciente));
        }
    }

    private void encolar(String id, Runnable trabajo) {
        if (!encolados.add(id)) return;
        try {
            executor.execute(() -> {
                try { trabajo.run(); }
                catch (RuntimeException error) {
                    // Storage failures leave a committed lease for recovery, never a false provider outage.
                    log.warn("Chat recovery deferred chatId={} errorType={}", id, error.getClass().getSimpleName());
                } finally { encolados.remove(id); }
            });
        } catch (RuntimeException error) { encolados.remove(id); }
    }

    private void procesarTexto(String id, String paciente, String solicitud) {
        Integer intento = tx.execute(status -> {
            Chat chat = propio(id, paciente);
            if (chat.isFinalizado() || !solicitud.equals(chat.getSolicitudTurnoTextoId())
                    || (chat.getEstadoTurnoTexto() != EstadoTurnoTexto.PENDIENTE
                    && chat.getEstadoTurnoTexto() != EstadoTurnoTexto.NO_DISPONIBLE) || !vencido(chat)) return 0;
            if (chat.isTurnoTextoEnCurso()) {
                if (leaseVigente(chat.getTurnoTextoIniciadoEn())) return 0;
                chat.setFallosInternosTurnoTexto(chat.getFallosInternosTurnoTexto() + 1);
            }
            if (chat.getFallosInternosTurnoTexto() >= MAX_FALLOS_INTERNOS) { chatService.cerrarParcialTexto(chat); return 0; }
            chat.setEstadoTurnoTexto(EstadoTurnoTexto.PENDIENTE);
            chat.setIntentosTurnoTexto(chat.getIntentosTurnoTexto() + 1);
            chat.setTurnoTextoEnCurso(true);
            chat.setTurnoTextoIniciadoEn(LocalDateTime.now());
            chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
            chat.setProximoReintentoEn(null);
            chat.avanzarVersionEstado();
            return chat.getIntentosTurnoTexto();
        });
        if (intento == null || intento == 0) return;
        try { chatService.procesarTurnoTextoPersistido(id, paciente, solicitud, intento); }
        catch (RuntimeException error) {
            tx.executeWithoutResult(status -> {
                Chat chat = propio(id, paciente);
                if (!solicitud.equals(chat.getSolicitudTurnoTextoId()) || chat.getIntentosTurnoTexto() != intento
                        || !chat.isTurnoTextoEnCurso() || chat.isFinalizado()) return;
                chat.setTurnoTextoEnCurso(false);
                chat.setTurnoTextoIniciadoEn(null);
                if (error instanceof ChatFinalizadoException) { abandonar(chat); return; }
                if (apiNoDisponible(error)) {
                    chat.setEstadoTurnoTexto(EstadoTurnoTexto.NO_DISPONIBLE);
                    chat.setErrorTurnoTexto("api_no_disponible");
                    programar(chat, error, demoraTextoMs);
                } else {
                    chat.setFallosInternosTurnoTexto(chat.getFallosInternosTurnoTexto() + 1);
                    chat.setErrorTurnoTexto("procesamiento_interno");
                    if (configuracion(error) || chat.getFallosInternosTurnoTexto() >= MAX_FALLOS_INTERNOS) chatService.cerrarParcialTexto(chat);
                    else programar(chat, error, demoraTextoMs);
                }
                chat.avanzarVersionEstado();
            });
            log.warn("Text turn recovered chatId={} attempt={} category={}", id, intento, categoria(error));
        }
    }

    private void procesarClasificacion(String id, String paciente) {
        Integer intento = tx.execute(status -> {
            Chat chat = propio(id, paciente);
            if (!chat.isFinalizado() || chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE
                    || !chat.isRecuperacionAutomaticaClasificacion() || !vencido(chat)) return 0;
            if (chat.isClasificacionAutomaticaEnCurso()) {
                if (leaseVigente(chat.getClasificacionIniciadaEn())) return 0;
                chat.setFallosInternosClasificacion(chat.getFallosInternosClasificacion() + 1);
            }
            if (chat.getFallosInternosClasificacion() >= MAX_FALLOS_INTERNOS) { chatService.marcarParcial(chat); return 0; }
            chat.setIntentosClasificacionAutomaticos(chat.getIntentosClasificacionAutomaticos() + 1);
            chat.setClasificacionAutomaticaEnCurso(true);
            chat.setClasificacionIniciadaEn(LocalDateTime.now());
            chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
            chat.setProximoReintentoEn(null);
            chat.avanzarVersionEstado();
            return chat.getIntentosClasificacionAutomaticos();
        });
        if (intento == null || intento == 0) return;
        try { chatService.procesarClasificacionDurable(id, paciente, intento); }
        catch (RuntimeException error) {
            tx.executeWithoutResult(status -> {
                Chat chat = propio(id, paciente);
                if (chat.getEstadoClasificacion() != EstadoClasificacionChat.PENDIENTE
                        || !chat.isClasificacionAutomaticaEnCurso() || chat.getIntentosClasificacionAutomaticos() != intento) return;
                if (error instanceof ChatFinalizadoException) { abandonar(chat); return; }
                chatService.registrarFalloClasificacion(chat, error, demoraClasificacionMs, demoraApiMs);
            });
            log.warn("Classification recovered chatId={} attempt={} category={}", id, intento, categoria(error));
        }
    }

    private void programar(Chat chat, RuntimeException error, long demoraInternaMs) {
        RecuperacionChatPolicy.programar(chat, error, demoraInternaMs, demoraApiMs);
    }

    private static void abandonar(Chat chat) {
        chat.setFinalizado(true);
        chat.setEstadoTurnoTexto(EstadoTurnoTexto.COMPLETADA);
        chat.setTurnoTextoEnCurso(false);
        if (chat.getResultadoTriageJson() == null && chat.getMensajes().stream().anyMatch(m -> m.getAutor() == AutorMensaje.PACIENTE)) {
            chat.setCierreParcial(true);
            chat.setEstadoClasificacion(EstadoClasificacionChat.PARCIAL);
        }
        ChatService.limpiarRecuperacion(chat);
        chat.avanzarVersionEstado();
    }

    private Chat propio(String id, String paciente) { return repoChat.findForUpdate(Long.valueOf(id), paciente).orElseThrow(ChatNoEncontradoException::new); }
    private static boolean vencido(Chat chat) { return chat.getProximoReintentoEn() == null || !chat.getProximoReintentoEn().isAfter(LocalDateTime.now()); }
    private static boolean leaseVigente(LocalDateTime inicio) { return inicio != null && inicio.isAfter(LocalDateTime.now().minusSeconds(150)); }
    private static boolean apiNoDisponible(RuntimeException error) { return error instanceof ProveedorIaException p && p.isApiNoDisponible(); }
    private static boolean configuracion(RuntimeException error) { return error instanceof ProveedorIaException p && p.getCategoria() == ProveedorIaException.Categoria.CONFIGURACION; }
    private static String categoria(RuntimeException error) { return error instanceof ProveedorIaException p ? p.getCategoria().name() : "OPERACION_INTERNA"; }
    private static ConflictoDeEstadoException conflicto(String mensaje) { return new ConflictoDeEstadoException(mensaje); }
    @PreDestroy public void apagarExecutor() { executor.shutdown(); }
}
