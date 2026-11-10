package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.DiscrepanciaSintomas;
import com.pretriage.backend.model.consultas.EstadoDiscrepanciaSintomas;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface RepoDiscrepanciasSintomas extends JpaRepository<DiscrepanciaSintomas, Long> {
    Optional<DiscrepanciaSintomas> findByAtencionMedicaId(Long atencionId);

    boolean existsByIdAndEstado(Long id, EstadoDiscrepanciaSintomas estado);

    long countByDniAndEstado(String dni, EstadoDiscrepanciaSintomas estado);

    Optional<DiscrepanciaSintomas> findFirstByDniAndEstadoOrderByRegistradaEnDescIdDesc(
            String dni, EstadoDiscrepanciaSintomas estado);

    List<DiscrepanciaSintomas> findByAtencionMedicaSesionAtencionMedicaHospitalIdOrderByRegistradaEnDescIdDesc(
            Long hospitalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DiscrepanciaSintomas> findByIdAndAtencionMedicaSesionAtencionMedicaHospitalId(
            Long id, Long hospitalId);
}
