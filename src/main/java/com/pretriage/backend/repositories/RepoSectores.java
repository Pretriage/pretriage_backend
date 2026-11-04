package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Sector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoSectores extends JpaRepository<Sector, Long> {

    boolean existsByHospitalIdAndNombreIgnoreCase(Long hospitalId, String nombre);

    boolean existsByHospitalIdAndNombreIgnoreCaseAndIdNot(Long hospitalId, String nombre, Long idNot);

    List<Sector> findByHospitalIdOrderByNombreAsc(Long hospitalId);

    Optional<Sector> findByIdAndHospitalId(Long id, Long hospitalId);

    List<Sector> findByHospitalIdAndEspecialidadId(Long hospitalId, Long especialidadId);

    List<Sector> findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(Long hospitalId, Long especialidadId);

    List<Sector> findByHospitalIdAndEspecialidadCodigoAndActivaTrueOrderByNombreAsc(Long hospitalId,
            String codigoEspecialidad);
}
