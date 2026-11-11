package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EstadoConsultaPacienteDTO;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EsperaPacienteService {

    private static final int MINUTOS_MAXIMOS_EN_ESPERA = 60;
    /** Minutos que se suman al plazo vigente cada vez que el paciente pide más tiempo para llegar. */
    static final int MINUTOS_EXTENSION_ATRASO = 30;
    /** Cantidad máxima de veces que el paciente puede pedir más tiempo después de no presentarse al llamado. */
    static final int MAX_EXTENSIONES_ATRASO = 2;

    private final PacienteService pacienteService;
    private final RepoEntradasCola repoEntradasCola;
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoChat repoChat;
    private final EstimacionAtencionService estimacionAtencionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public EstadoConsultaPacienteDTO ausentarme(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        EntradaCola entrada = obtenerEntradaActivaPaciente(paciente);
        ConsultaMedica consulta = repoConsultasMedicas.findByIdForUpdate(entrada.getConsultaMedica().getId())
                .orElseThrow(() -> new NoSuchElementException("La consulta ya no existe"));
        entityManager.refresh(consulta);
        entityManager.refresh(entrada);
        validarEstado(entrada, EstadoEntradaCola.EN_COLA);

        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        entrada.setTipoPausa(TipoPausaCola.ESPERA_MANUAL);
        entrada.setFechaHoraSalidaTemporal(LocalDateTime.now());
        entrada.setFechaHoraLimiteRespuesta(LocalDateTime.now().plusMinutes(MINUTOS_MAXIMOS_EN_ESPERA));
        consulta.setEstadoConsulta(EstadoConsulta.EN_ESPERA);

        repoConsultasMedicas.save(consulta);
        return mapear(repoEntradasCola.save(entrada));
    }

    /**
     * El paciente no estaba cuando lo llamaron y avisa que va a demorar: se suman
     * {@link #MINUTOS_EXTENSION_ATRASO} minutos al final del plazo vigente (no desde ahora).
     */
    @Transactional
    public EstadoConsultaPacienteDTO estoyAtrasado(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        EntradaCola entrada = obtenerEntradaActivaPaciente(paciente);
        validarEstado(entrada, EstadoEntradaCola.EN_ESPERA);
        if (entrada.getTipoPausa() != TipoPausaCola.AUSENTE_AL_LLAMADO) {
            throw new IllegalStateException("Solo puede pedir más tiempo si no estaba cuando lo llamaron");
        }

        extenderPlazo(entrada);
        entrada.setEstado(EstadoEntradaCola.ATRASADO);
        entrada.setTipoPausa(TipoPausaCola.ATRASADO_CONFIRMADO);
        entrada.getConsultaMedica().setEstadoConsulta(EstadoConsulta.ATRASADO);

        repoConsultasMedicas.save(entrada.getConsultaMedica());
        return mapear(repoEntradasCola.save(entrada));
    }

    /** Vuelve a pedir más tiempo: se suma al final del plazo vigente, hasta {@link #MAX_EXTENSIONES_ATRASO} veces. */
    @Transactional
    public EstadoConsultaPacienteDTO sigoAsistiendo(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        EntradaCola entrada = obtenerEntradaActivaPaciente(paciente);
        validarEstado(entrada, EstadoEntradaCola.ATRASADO);

        extenderPlazo(entrada);
        return mapear(repoEntradasCola.save(entrada));
    }

    private void extenderPlazo(EntradaCola entrada) {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime plazoVigente = plazoVigente(entrada, ahora);
        if (!plazoVigente.isAfter(ahora)) {
            throw new IllegalStateException("El plazo para llegar ya venció");
        }
        if (extensionesUsadas(entrada) >= MAX_EXTENSIONES_ATRASO) {
            throw new IllegalStateException("Ya pediste más tiempo " + MAX_EXTENSIONES_ATRASO
                    + " veces. Si no llegás antes del horario indicado, tu turno se cancelará.");
        }
        entrada.setFechaHoraUltimaRepregunta(ahora);
        entrada.setFechaHoraLimiteRespuesta(plazoVigente.plusMinutes(MINUTOS_EXTENSION_ATRASO));
    }

    private LocalDateTime plazoVigente(EntradaCola entrada, LocalDateTime ahora) {
        if (entrada.getFechaHoraLimiteRespuesta() != null) return entrada.getFechaHoraLimiteRespuesta();
        if (entrada.getFechaHoraSalidaTemporal() != null) {
            return entrada.getFechaHoraSalidaTemporal().plusMinutes(MINUTOS_MAXIMOS_EN_ESPERA);
        }
        return ahora;
    }

    /** Las extensiones se deducen del plazo: cada una suma {@link #MINUTOS_EXTENSION_ATRASO} minutos al plazo inicial. */
    private int extensionesUsadas(EntradaCola entrada) {
        if (entrada.getFechaHoraSalidaTemporal() == null || entrada.getFechaHoraLimiteRespuesta() == null) return 0;
        long minutosExtra = Duration.between(
                entrada.getFechaHoraSalidaTemporal().plusMinutes(MINUTOS_MAXIMOS_EN_ESPERA),
                entrada.getFechaHoraLimiteRespuesta()).toMinutes();
        return (int) Math.max(0, (minutosExtra + 1) / MINUTOS_EXTENSION_ATRASO);
    }

    @Transactional
    public EstadoConsultaPacienteDTO llegue(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        EntradaCola entrada = obtenerEntradaActivaPaciente(paciente);

        boolean ausenteAlLlamado = entrada.getEstado() == EstadoEntradaCola.EN_ESPERA
                && entrada.getTipoPausa() == TipoPausaCola.AUSENTE_AL_LLAMADO;
        if (entrada.getEstado() == EstadoEntradaCola.ATRASADO || ausenteAlLlamado) {
            // Ya lo habían llamado: vuelve primero entre los pacientes de su misma prioridad.
            entrada.setOrdenRelativo(obtenerOrdenParaPrimerLugarDePrioridad(entrada));
        } else if (entrada.getEstado() != EstadoEntradaCola.EN_ESPERA || entrada.getTipoPausa() != TipoPausaCola.ESPERA_MANUAL) {
            throw new IllegalStateException("El paciente no puede volver a la cola desde el estado actual");
        }

        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        entrada.setTipoPausa(null);
        entrada.setFechaHoraIngreso(LocalDateTime.now());
        entrada.setFechaHoraLimiteRespuesta(null);
        entrada.setFechaHoraUltimaRepregunta(null);
        entrada.getConsultaMedica().setEstadoConsulta(EstadoConsulta.EN_COLA);
        entrada.getConsultaMedica().setMedico(null);
        entrada.getConsultaMedica().setSala(null);

        repoConsultasMedicas.save(entrada.getConsultaMedica());
        return mapear(repoEntradasCola.save(entrada));
    }

    @Transactional
    public EstadoConsultaPacienteDTO obtenerEstado(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        return mapear(obtenerEntradaActivaPaciente(paciente));
    }

    @Transactional
    public EstadoConsultaPacienteDTO cancelarSeleccion(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        EntradaCola entradaLeida = repoEntradasCola
                .findFirstByConsultaMedicaPacienteIdOrderByIdDesc(paciente.getId())
                .orElseThrow(() -> new NoSuchElementException(
                        "El paciente no tiene una seleccion de hospital activa para cancelar"));

        ConsultaMedica consultaLeida = entradaLeida.getConsultaMedica();
        List<Chat> chatsBloqueados = bloquearChatsDeCancelacion(auth0Id, consultaLeida);
        ConsultaMedica consulta = repoConsultasMedicas.findByIdForUpdate(consultaLeida.getId())
                .orElseThrow(() -> new NoSuchElementException("La consulta ya no existe"));
        entityManager.refresh(consulta, LockModeType.PESSIMISTIC_WRITE);
        if (consulta.getPaciente() == null || !consulta.getPaciente().getId().equals(paciente.getId())) {
            throw new ConflictoDeEstadoException("La consulta ya no pertenece a la selección activa");
        }

        EntradaCola entrada = repoEntradasCola.findByConsultaMedicaId(consulta.getId())
                .orElseThrow(() -> new NoSuchElementException("La consulta ya no está en cola"));
        entityManager.refresh(entrada);
        EntradaCola ultimaEntrada = repoEntradasCola
                .findFirstByConsultaMedicaPacienteIdOrderByIdDesc(paciente.getId())
                .orElseThrow(() -> new NoSuchElementException(
                        "El paciente no tiene una seleccion de hospital activa para cancelar"));
        if (!Objects.equals(entrada.getId(), entradaLeida.getId())
                || !Objects.equals(ultimaEntrada.getId(), entrada.getId())) {
            throw new ConflictoDeEstadoException("La selección activa cambió mientras se procesaba la cancelación");
        }

        if (entrada.getEstado() == EstadoEntradaCola.EN_ATENCION) {
            throw new ConflictoDeEstadoException("No puede cancelar una consulta con atencion en curso");
        }
        if (entrada.getEstado() == EstadoEntradaCola.FINALIZADA) {
            throw new ConflictoDeEstadoException("La consulta ya finalizo y no se puede cancelar");
        }
        if (entrada.getEstado() == EstadoEntradaCola.EN_REVISION) {
            throw new ConflictoDeEstadoException("El médico está revisando tu consulta");
        }
        if (entrada.getEstado() == EstadoEntradaCola.LLAMADO) {
            throw new ConflictoDeEstadoException("El médico te esta llamando, no se puede cancelar. Si quieres cancelarlo espera a que el médico te marque ausente en el sistema y podrás cancelarlo");
        }
        if (entrada.getEstado() == EstadoEntradaCola.CANCELADA) {
            return mapear(entrada);
        }

        finalizarChatsCancelados(chatsBloqueados);
        entrada.setEstado(EstadoEntradaCola.CANCELADA);
        entrada.setTipoPausa(null);
        entrada.setFechaHoraLimiteRespuesta(null);
        entrada.setFechaHoraUltimaRepregunta(null);
        consulta.setEstadoConsulta(EstadoConsulta.CANCELADA);
        consulta.setMedico(null);
        consulta.setSala(null);
        repoConsultasMedicas.save(consulta);
        repoEntradasCola.save(entrada);
        return mapear(entrada);
    }

    /** Lock order must match triage completion: Chat rows by id, then ConsultaMedica. */
    private List<Chat> bloquearChatsDeCancelacion(String auth0Id, ConsultaMedica consulta) {
        List<Long> ids = new ArrayList<>();
        repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc(auth0Id)
                .map(Chat::getId).ifPresent(ids::add);
        repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse(auth0Id)
                .stream().map(Chat::getId).forEach(ids::add);
        Optional.ofNullable(consulta.getChat()).map(Chat::getId).ifPresent(ids::add);

        return ids.stream().distinct().sorted(Comparator.naturalOrder())
                .map(id -> repoChat.findForUpdate(id, auth0Id))
                .flatMap(Optional::stream)
                .peek(chat -> entityManager.refresh(chat, LockModeType.PESSIMISTIC_WRITE)).toList();
    }

    private void finalizarChatsCancelados(List<Chat> chats) {
        chats.forEach(chat -> {
                    chat.setFinalizado(true);
                    if (chat.getEstadoClasificacion() == com.pretriage.backend.model.chat.EstadoClasificacionChat.PENDIENTE) {
                        chat.setEstadoClasificacion(com.pretriage.backend.model.chat.EstadoClasificacionChat.PARCIAL);
                        chat.setCierreParcial(true);
                    }
                    chat.setEstadoTurnoTexto(com.pretriage.backend.model.chat.EstadoTurnoTexto.COMPLETADA);
                    chat.setTurnoTextoEnCurso(false);
                    ChatService.limpiarRecuperacion(chat);
                    chat.avanzarVersionEstado();
                    repoChat.save(chat);
                });
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cancelarAtrasadosSinRespuesta() {
        List<EntradaCola> vencidas = repoEntradasCola.findByEstadoAndTipoPausaAndFechaHoraLimiteRespuestaBefore(
                EstadoEntradaCola.ATRASADO,
                TipoPausaCola.ATRASADO_CONFIRMADO,
                LocalDateTime.now());

        cancelarEntradas(vencidas);
    }


    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cancelarEsperasVencidas() {
        List<EntradaCola> vencidas = repoEntradasCola.findByEstadoAndFechaHoraSalidaTemporalBefore(
                EstadoEntradaCola.EN_ESPERA,
                LocalDateTime.now().minusMinutes(MINUTOS_MAXIMOS_EN_ESPERA));

        cancelarEntradas(vencidas);
    }

    private void cancelarEntradas(List<EntradaCola> entradas) {
        entradas.forEach(entrada -> {
            ConsultaMedica consulta = entrada.getConsultaMedica();
            List<Chat> chats = consulta.getPaciente().getUsuarioAuth() == null ? List.of()
                    : bloquearChatsDeCancelacion(consulta.getPaciente().getUsuarioAuth().getId(), consulta);
            consulta = repoConsultasMedicas.findByIdForUpdate(consulta.getId()).orElseThrow();
            entityManager.refresh(consulta, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(entrada);
            LocalDateTime ahora = LocalDateTime.now();
            boolean vencida = entrada.getEstado() == EstadoEntradaCola.ATRASADO
                    && entrada.getTipoPausa() == TipoPausaCola.ATRASADO_CONFIRMADO
                    && entrada.getFechaHoraLimiteRespuesta() != null && entrada.getFechaHoraLimiteRespuesta().isBefore(ahora)
                    || entrada.getEstado() == EstadoEntradaCola.EN_ESPERA && entrada.getFechaHoraSalidaTemporal() != null
                    && entrada.getFechaHoraSalidaTemporal().isBefore(ahora.minusMinutes(MINUTOS_MAXIMOS_EN_ESPERA));
            if (!vencida || consulta.getEstadoConsulta() == EstadoConsulta.CANCELADA
                    || consulta.getEstadoConsulta() == EstadoConsulta.FINALIZADA) return;
            finalizarChatsCancelados(chats);
            entrada.setEstado(EstadoEntradaCola.CANCELADA);
            consulta.setEstadoConsulta(EstadoConsulta.CANCELADA);
            repoConsultasMedicas.save(consulta);
            repoEntradasCola.save(entrada);
        });
    }
    private Paciente obtenerPaciente(String auth0Id) {
        return pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos de paciente"));
    }

    private EntradaCola obtenerEntradaActivaPaciente(Paciente paciente) {
        return repoEntradasCola.findFirstByConsultaMedicaPacienteIdAndEstadoInOrderByIdDesc(
                        paciente.getId(),
                        List.of(EstadoEntradaCola.EN_COLA,
                                EstadoEntradaCola.EN_REVISION,
                                EstadoEntradaCola.LLAMADO,
                                EstadoEntradaCola.EN_ESPERA,
                                EstadoEntradaCola.ATRASADO,
                                EstadoEntradaCola.EN_ATENCION))
                .orElseThrow(() -> new NoSuchElementException("El paciente no tiene una consulta activa en cola"));
    }

    private void validarEstado(EntradaCola entrada, EstadoEntradaCola estadoEsperado) {
        if (entrada.getEstado() != estadoEsperado) {
            throw new IllegalStateException("Estado de cola invalido para la accion solicitada");
        }
    }

    private long obtenerOrdenParaPrimerLugarDePrioridad(EntradaCola entrada) {
        return repoEntradasCola
                .findFirstByGestorDeColaIdAndEstadoAndPrioridadOrderByOrdenRelativoAsc(
                        entrada.getGestorDeCola().getId(),
                        EstadoEntradaCola.EN_COLA,
                        entrada.getPrioridad())
                .map(primera -> primera.getOrdenRelativo() - 1)
                .orElse(entrada.getOrdenRelativo());
    }

    private EstadoConsultaPacienteDTO mapear(EntradaCola entrada) {
        EstadoConsultaPacienteDTO dto = new EstadoConsultaPacienteDTO();
        dto.setConsultaId(entrada.getConsultaMedica().getId());
        dto.setEstadoConsulta(entrada.getConsultaMedica().getEstadoConsulta());
        dto.setEstadoEntradaCola(entrada.getEstado());
        dto.setTipoPausa(entrada.getTipoPausa());
        dto.setFechaHoraLimiteRespuesta(entrada.getFechaHoraLimiteRespuesta());
        boolean puedePedirTiempo = entrada.getEstado() == EstadoEntradaCola.ATRASADO
                || entrada.getEstado() == EstadoEntradaCola.EN_ESPERA
                && entrada.getTipoPausa() == TipoPausaCola.AUSENTE_AL_LLAMADO;
        dto.setExtensionesRestantes(puedePedirTiempo
                ? Math.max(0, MAX_EXTENSIONES_ATRASO - extensionesUsadas(entrada)) : 0);
        dto.setMinutosPorExtension(MINUTOS_EXTENSION_ATRASO);
        Sector sector = entrada.getConsultaMedica().getSector();
        dto.setSectorId(sector != null ? sector.getId() : null);
        dto.setNombreSector(sector != null ? sector.getNombre() : null);
        if (entrada.getEstado() == EstadoEntradaCola.EN_COLA) {
            dto.setTiempoEstimadoAtencion(estimacionAtencionService.calcularPara(entrada.getConsultaMedica()));
        }
        return dto;
    }
}

