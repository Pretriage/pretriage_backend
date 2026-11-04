package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoEntradasCola extends JpaRepository<EntradaCola, Long> {

    Optional<EntradaCola> findByConsultaMedicaId(Long idConsultaMedica);

    Optional<EntradaCola> findFirstByConsultaMedicaPacienteIdAndEstadoInOrderByIdDesc(
            Long idPaciente, Collection<EstadoEntradaCola> estados);

    Optional<EntradaCola> findFirstByConsultaMedicaPacienteIdOrderByIdDesc(Long idPaciente);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EntradaCola> findFirstByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAsc(Long idGestorDeCola, EstadoEntradaCola estado);

    Optional<EntradaCola> findFirstByGestorDeColaIdOrderByOrdenRelativoDesc(Long idGestorDeCola);

    Optional<EntradaCola> findFirstByGestorDeColaIdAndEstadoAndPrioridadOrderByOrdenRelativoAsc(Long idGestorDeCola, EstadoEntradaCola estado, int prioridad);

    List<EntradaCola> findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(Long idGestorDeCola, EstadoEntradaCola estado);

    List<EntradaCola> findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
            Long idGestorDeCola, EstadoEntradaCola estado, String numeroDocumento);

    boolean existsByConsultaMedicaMedicoIdAndEstadoIn(Long medicoId, Collection<EstadoEntradaCola> estados);

    Optional<EntradaCola> findFirstByConsultaMedicaMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraLlamadoDesc(
            String auth0Id, Collection<EstadoEntradaCola> estados);

    List<EntradaCola> findByEstadoAndFechaHoraSalidaTemporalBefore(EstadoEntradaCola estado, LocalDateTime limite);

    List<EntradaCola> findByEstadoAndTipoPausaAndFechaHoraLimiteRespuestaBefore(EstadoEntradaCola estado, TipoPausaCola tipoPausa, LocalDateTime fechaHoraLimiteRespuesta);

    long countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado(
            Long hospitalId, Long especialidadId, EstadoEntradaCola estado);

    long countByGestorDeColaSectorIdAndEstado(Long sectorId, EstadoEntradaCola estado);

    /**
     * Timestamps de ingreso a la cola de las entradas del hospital en el rango {@code [desde, hasta)}.
     * El tamaño de la lista es la cantidad de ingresos del período (ancla del embudo de métricas).
     */
    @Query("""
            select e.fechaHoraIngreso from EntradaCola e
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
            """)
    List<LocalDateTime> findFechasHoraIngresoByHospitalEnRango(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);
}
