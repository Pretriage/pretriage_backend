package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.RevisionPrioridadConsulta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoRevisionesPrioridadConsulta extends JpaRepository<RevisionPrioridadConsulta, Long> {
    Optional<RevisionPrioridadConsulta> findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(Long consultaId);
}
