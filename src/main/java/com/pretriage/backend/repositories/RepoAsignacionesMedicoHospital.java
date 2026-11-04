package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.AsignacionMedicoHospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RepoAsignacionesMedicoHospital extends JpaRepository<AsignacionMedicoHospital, Long> {

    boolean existsByMedicoIdAndHospitalIdAndEspecialidadCodigo(Long idMedico, Long idHospital, String codigoEspecialidad);

    List<AsignacionMedicoHospital> findByMedicoId(Long idMedico);
    List<AsignacionMedicoHospital> findByMedicoUsuarioAuthId(String auth0Id);
}
