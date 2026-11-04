package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Sala;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoSalas extends JpaRepository<Sala, Long> {

    List<Sala> findByHospitalIdAndEspecialidadCodigoAndActivaTrue(Long idHospital, String codigoEspecialidad);
    List<Sala> findByHospitalIdOrderByNombreAsc(Long hospitalId);
    Optional<Sala> findByIdAndHospitalId(Long id, Long hospitalId);
    boolean existsByHospitalIdAndEspecialidadIdAndActivaTrue(Long hospitalId, Long especialidadId);
    boolean existsByHospitalIdAndSectorIdAndNombreIgnoreCase(Long hospitalId, Long sectorId, String nombre);
    boolean existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot(Long hospitalId, String nombre, Long sectorId,
            Long idNot);
    List<Sala> findBySectorId(Long sectorId);

    List<Sala> findBySectorIdAndEspecialidadCodigoAndActivaTrue(Long sectorId, String codigoEspecialidad);
}
