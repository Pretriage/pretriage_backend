package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Sector;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoSectores extends JpaRepository<Sector, Long> {

    boolean existsByHospitalIdAndNombreIgnoreCaseAndArchivadoFalse(Long hospitalId, String nombre);

    boolean existsByHospitalIdAndNombreIgnoreCaseAndIdNotAndArchivadoFalse(
            Long hospitalId, String nombre, Long idNot);

    List<Sector> findByHospitalIdAndArchivadoFalseOrderByNombreAsc(Long hospitalId);

    Optional<Sector> findByIdAndHospitalId(Long id, Long hospitalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sector s where s.id = :sectorId and s.hospital.id = :hospitalId")
    Optional<Sector> findByIdAndHospitalIdForUpdate(@Param("sectorId") Long sectorId,
            @Param("hospitalId") Long hospitalId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Sector s where s.id = :sectorId and s.activa = true and s.archivado = false")
    Optional<Sector> findByIdForOperationalUse(@Param("sectorId") Long sectorId);

    List<Sector> findByHospitalIdAndEspecialidadId(Long hospitalId, Long especialidadId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select s from Sector s
            where s.hospital.id = :hospitalId and s.especialidad.id = :especialidadId
              and s.activa = true and s.archivado = false
            order by s.nombre asc, s.id asc
            """)
    List<Sector> findSectoresActivosParaAsignacion(@Param("hospitalId") Long hospitalId,
            @Param("especialidadId") Long especialidadId);

    List<Sector> findByHospitalIdAndEspecialidadCodigoAndActivaTrueAndArchivadoFalseOrderByNombreAsc(
            Long hospitalId, String codigoEspecialidad);
}
