package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.SancionDigitalPaciente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RepoSancionesDigitales extends JpaRepository<SancionDigitalPaciente, Long> {
    Optional<SancionDigitalPaciente> findFirstByDniOrderByIdDesc(String dni);
}
