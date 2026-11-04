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

    public SseEmitter conectar(String auth0Id, Long consultaId) {
        if (!repoConsultasMedicas.existsByIdAndPacienteUsuarioAuthId(consultaId, auth0Id)) {
            throw new AccessDeniedException("No tiene permisos sobre la consulta");
        }
        EntradaCola entrada = obtenerEntradaActiva(consultaId);
        SseEmitter emitter = new SseEmitter(0L);
        conexiones.computeIfAbsent(consultaId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> desconectar(consultaId, emitter));
        emitter.onTimeout(() -> desconectar(consultaId, emitter));
        emitter.onError(error -> desconectar(consultaId, emitter));
        enviar(consultaId, emitter, "tiempo-estimado", estimacionAtencionService.calcularPara(entrada.getConsultaMedica()));
        return emitter;
    }

    @Scheduled(fixedRate = 15000)
    public void actualizarEstimaciones() {
        conexiones.forEach((consultaId, emitters) -> {
            try {
                EntradaCola entrada = obtenerEntradaActiva(consultaId);
                TiempoEstimadoAtencionResponse estimacion = estimacionAtencionService.calcularPara(entrada.getConsultaMedica());
                emitters.forEach(emitter -> enviar(consultaId, emitter, "tiempo-estimado", estimacion));
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
