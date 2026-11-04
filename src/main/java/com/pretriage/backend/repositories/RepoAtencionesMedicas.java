package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.repositories.projections.AtencionEmbudoProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface RepoAtencionesMedicas extends JpaRepository<AtencionMedica, Long> {
    Optional<AtencionMedica> findByConsultaMedicaIdAndEstado(Long consultaId, EstadoAtencionMedica estado);
    boolean existsBySesionAtencionMedicaIdAndEstado(Long sesionId, EstadoAtencionMedica estado);
    List<AtencionMedica> findBySesionAtencionMedicaMedicoUsuarioAuthIdOrderByFechaHoraInicioDesc(String auth0Id);

    /**
     * Atenciones en el estado indicado (para métricas: FINALIZADA) cuya {@code EntradaCola}
     * pertenece al hospital y tiene {@code fechaHoraIngreso} en el rango {@code [desde, hasta)}.
     */
    @Query("""
            select new com.pretriage.backend.repositories.projections.AtencionEmbudoProjection(
                e.fechaHoraIngreso, a.fechaHoraInicio, a.fechaHoraFin)
            from EntradaCola e
            join AtencionMedica a on a.consultaMedica = e.consultaMedica
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
              and a.estado = :estado
            """)
    List<AtencionEmbudoProjection> findAtencionesDelEmbudo(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta,
            @Param("estado") EstadoAtencionMedica estado);
}
