package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.EstudioClinico;
import com.pretriage.backend.model.personas.Paciente;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoEstudiosClinicos extends JpaRepository<EstudioClinico, Long> {


    List<EstudioClinico> findAllByPacienteAndActivoTrue(Paciente paciente);

    Optional<EstudioClinico> findByIdAndPacienteAndActivoTrue(Long idEstudioClinico, Paciente paciente);

    List<EstudioClinico> findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(Long pacienteId, Pageable pageable);
}
