package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.repositories.projections.ConteoPorNivelProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoConsultasMedicas extends JpaRepository<ConsultaMedica, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConsultaMedica c where c.id = :id")
    Optional<ConsultaMedica> findByIdForUpdate(@Param("id") Long id);

    boolean existsByIdAndPacienteUsuarioAuthId(Long consultaId, String auth0Id);

    Optional<ConsultaMedica> findByPacienteIdAndEstadoConsultaEquals(Long idPaciente, EstadoConsulta estadoConsulta);

    Optional<ConsultaMedica> findFirstByPacienteIdAndEstadoConsultaIn(Long idPaciente,
            Collection<EstadoConsulta> estadosConsulta);

    List<ConsultaMedica> findByPacienteIdAndChatIsNotNull(Long idPaciente);

    boolean existsBySalaIdInAndEstadoConsultaNotIn(Collection<Long> salaIds, Collection<EstadoConsulta> estados);

    boolean existsBySectorIdAndEstadoConsultaNotIn(Long sectorId, Collection<EstadoConsulta> estados);

    boolean existsBySectorId(Long sectorId);

    boolean existsBySalaIdIn(Collection<Long> salaIds);

    // --- Métricas: todas restringidas a las consultas cuya EntradaCola del hospital ingresó en [desde, hasta) ---

    @Query("""
            select new com.pretriage.backend.repositories.projections.ConteoPorNivelProjection(
                c.nivelDeGravedadBot, count(c))
            from EntradaCola e join e.consultaMedica c
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
              and c.chat is not null
              and (c.chat.estadoClasificacion = com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA
                   or (c.chat.estadoClasificacion is null and c.chat.resultadoTriageJson is not null))
            group by c.nivelDeGravedadBot
            """)
    List<ConteoPorNivelProjection> contarIngresadosPorNivelBot(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    @Query("""
            select new com.pretriage.backend.repositories.projections.ConteoPorNivelProjection(
                c.nivelDeGravedadMedico, count(c))
            from EntradaCola e join e.consultaMedica c
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
            group by c.nivelDeGravedadMedico
            """)
    List<ConteoPorNivelProjection> contarIngresadosPorNivelMedico(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    @Query("""
            select count(c)
            from EntradaCola e join e.consultaMedica c
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
              and c.chat is not null
              and (c.chat.estadoClasificacion = com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA
                   or (c.chat.estadoClasificacion is null and c.chat.resultadoTriageJson is not null))
            """)
    long contarIngresadosConPretriage(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    @Query("""
            select count(c)
            from EntradaCola e join e.consultaMedica c left join c.chat ch
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
              and (ch.id is null
                   or (ch.estadoClasificacion is not null
                       and ch.estadoClasificacion <> com.pretriage.backend.model.chat.EstadoClasificacionChat.COMPLETADA)
                   or (ch.estadoClasificacion is null and ch.resultadoTriageJson is null))
            """)
    long contarIngresadosSinPretriage(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);
}
