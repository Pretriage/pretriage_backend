package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoSesionesAtencionMedica extends JpaRepository<SesionAtencionMedica, Long> {

    boolean existsByMedicoIdAndEstadoIn(Long idMedico, Collection<EstadoSesionMedica> estados);

    boolean existsBySalaIdAndEstadoIn(Long idSala, Collection<EstadoSesionMedica> estados);

    Optional<SesionAtencionMedica> findByIdAndMedicoUsuarioAuthId(Long id, String auth0Id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SesionAtencionMedica s where s.id = :sesionId and s.medico.usuarioAuth.id = :auth0Id")
    Optional<SesionAtencionMedica> findByIdAndMedicoUsuarioAuthIdForUpdate(
            @Param("sesionId") Long sesionId,
            @Param("auth0Id") String auth0Id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SesionAtencionMedica s where s.id = :sesionId")
    Optional<SesionAtencionMedica> findByIdForUpdate(@Param("sesionId") Long sesionId);

    @Query("""
            select s from SesionAtencionMedica s
            left join fetch s.medico medico
            left join fetch medico.usuarioAuth usuarioAuth
            left join fetch s.sector sector
            left join fetch s.sala sala
            left join fetch sala.sector salaSector
            left join fetch s.especialidad especialidad
            where s.hospital.id = :hospitalId
              and s.estado in :estados
              and (sector.id = :sectorId or (sector is null and salaSector.id = :sectorId))
            order by s.fechaHoraInicio desc, s.id desc
            """)
    List<SesionAtencionMedica> findByHospitalIdAndSectorOrLegacySalaSectorAndEstadoIn(
            @Param("hospitalId") Long hospitalId,
            @Param("sectorId") Long sectorId,
            @Param("estados") Collection<EstadoSesionMedica> estados);

    Optional<SesionAtencionMedica> findFirstByMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraInicioDesc(
            String auth0Id, Collection<EstadoSesionMedica> estados);

    int countByHospitalIdAndEspecialidadIdAndEstado(Long idHospital, Long idEspecialidad, EstadoSesionMedica estado);

    int countByHospitalIdAndEspecialidadIdAndSectorIdAndEstado(
            Long idHospital, Long idEspecialidad, Long idSector, EstadoSesionMedica estado);

    boolean existsBySalaIdInAndEstadoIn(Collection<Long> salaIds, Collection<EstadoSesionMedica> estados);

    boolean existsBySectorIdAndEstadoIn(Long sectorId, Collection<EstadoSesionMedica> estados);

    boolean existsBySectorId(Long sectorId);

    boolean existsBySalaIdIn(Collection<Long> salaIds);
}

