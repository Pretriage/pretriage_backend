package com.pretriage.backend.repositories;

import com.pretriage.backend.model.acceso.AuditoriaHospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepoAuditoriasHospital extends JpaRepository<AuditoriaHospital, Long> {
    List<AuditoriaHospital> findTop50ByHospitalIdOrderByFechaDesc(Long hospitalId);
}
