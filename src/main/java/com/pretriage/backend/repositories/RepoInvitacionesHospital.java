package com.pretriage.backend.repositories;

import com.pretriage.backend.model.acceso.EstadoInvitacionHospital;
import com.pretriage.backend.model.acceso.InvitacionHospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoInvitacionesHospital extends JpaRepository<InvitacionHospital, Long> {
    Optional<InvitacionHospital> findByTokenHash(String tokenHash);
    List<InvitacionHospital> findByHospitalIdOrderByFechaCreacionDesc(Long hospitalId);
    boolean existsByHospitalIdAndEmailNormalizadoAndEstado(Long hospitalId, String email, EstadoInvitacionHospital estado);
}
