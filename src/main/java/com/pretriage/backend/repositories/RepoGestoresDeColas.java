package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.GestorDeCola;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoGestoresDeColas extends JpaRepository<GestorDeCola, Long> {
    Optional<GestorDeCola> findByHospitalId(Long idHospital);

    Optional<GestorDeCola> findByHospitalIdAndEspecialidadIdAndSectorId(Long idHospital, Long idEspecialidad,
            Long idSector);

    Optional<GestorDeCola> findBySectorId(Long idSector);
}
