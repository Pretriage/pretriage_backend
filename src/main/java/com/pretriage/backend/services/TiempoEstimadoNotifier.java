package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class TiempoEstimadoNotifier {
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoEntradasCola repoEntradasCola;
    private final EstimacionAtencionService estimacionAtencionService;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> conexiones = new ConcurrentHashMap<>();

    static final String EVENTO_TIEMPO_ESTIMADO = "tiempo-estimado";
    static final String EVENTO_ESTADO_CONSULTA = "estado-consulta";

    /**
     * Abre el canal de la consulta mientras siga activa (en cola, llamada, en pausa o en atención),
     * para que al paciente le lleguen los cambios de estado aunque ya no esté esperando en la fila.
     * Al conectarse recibe el estado actual; si la consulta ya terminó, lo recibe y el canal se cierra.
     */
    @Transactional(readOnly = true)
    public SseEmitter conectar(String auth0Id, Long consultaId) {
        if (!repoConsultasMedicas.existsByIdAndPacienteUsuarioAuthId(consultaId, auth0Id)) {
            throw new AccessDeniedException("No tiene permisos sobre la consulta");
        }
        EntradaCola entrada = repoEntradasCola.findByConsultaMedicaId(consultaId)
                .orElseThrow(() -> new NoSuchElementException("La consulta no esta en cola"));
        CambioEstadoConsultaEvento estadoActual = CambioEstadoConsultaEvento.desde(entrada);
        SseEmitter emitter = new SseEmitter(0L);
        if (estadoActual.esTerminal()) {
            enviar(consultaId, emitter, EVENTO_ESTADO_CONSULTA, estadoActual);
            emitter.complete();
            return emitter;
        }
        conexiones.computeIfAbsent(consultaId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> desconectar(consultaId, emitter));
        emitter.onTimeout(() -> desconectar(consultaId, emitter));
        emitter.onError(error -> desconectar(consultaId, emitter));
        enviar(consultaId, emitter, EVENTO_ESTADO_CONSULTA, estadoActual);
        if (entrada.getEstado() == EstadoEntradaCola.EN_COLA) {
            enviar(consultaId, emitter, EVENTO_TIEMPO_ESTIMADO,
                    estimacionAtencionService.calcularPara(entrada.getConsultaMedica()));
        }
        return emitter;
    }

    /**
     * Avisa al paciente que cambió el estado de su consulta. Se ejecuta recién cuando la transacción
     * que hizo el cambio confirma, para no avisar algo que después se deshizo.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void notificarCambioDeEstado(CambioEstadoConsultaEvento evento) {
        List<SseEmitter> emitters = conexiones.get(evento.consultaId());
        if (emitters == null) return;
        emitters.forEach(emitter -> enviar(evento.consultaId(), emitter, EVENTO_ESTADO_CONSULTA, evento));
        if (evento.esTerminal()) {
            emitters.forEach(SseEmitter::complete);
            conexiones.remove(evento.consultaId());
        }
    }

    @Scheduled(fixedRate = 15000)
    public void actualizarEstimaciones() {
        conexiones.forEach((consultaId, emitters) -> {
            try {
                EntradaCola entrada = obtenerEntradaActiva(consultaId);
                TiempoEstimadoAtencionResponse estimacion = estimacionAtencionService.calcularPara(entrada.getConsultaMedica());
                emitters.forEach(emitter -> enviar(consultaId, emitter, EVENTO_TIEMPO_ESTIMADO, estimacion));
            } catch (RuntimeException error) {
                log.debug("Ya no se puede estimar la consulta {}: {}", consultaId, error.getMessage());
            }
        });
    }

    @Scheduled(fixedRate = 30000)
    public void enviarHeartbeat() {
        conexiones.forEach((consultaId, emitters) -> emitters.forEach(emitter ->
                enviar(consultaId, emitter, "heartbeat", Map.of("consultaId", consultaId))));
    }

    private EntradaCola obtenerEntradaActiva(Long consultaId) {
        EntradaCola entrada = repoEntradasCola.findByConsultaMedicaId(consultaId)
                .orElseThrow(() -> new NoSuchElementException("La consulta no esta en cola"));
        if (entrada.getEstado() != EstadoEntradaCola.EN_COLA) {
            throw new IllegalStateException("La consulta ya no tiene una estimacion de espera activa");
        }
        return entrada;
    }

    private void enviar(Long consultaId, SseEmitter emitter, String evento, Object data) {
        try {
            emitter.send(SseEmitter.event().name(evento).data(data));
        } catch (IOException | IllegalStateException error) {
            desconectar(consultaId, emitter);
        }
    }

    private void desconectar(Long consultaId, SseEmitter emitter) {
        List<SseEmitter> emitters = conexiones.get(consultaId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) conexiones.remove(consultaId);
    }
}
