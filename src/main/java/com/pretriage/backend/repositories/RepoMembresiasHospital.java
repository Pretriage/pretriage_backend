package com.pretriage.backend.repositories;

import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.MembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoMembresiasHospital extends JpaRepository<MembresiaHospital, Long> {
    List<MembresiaHospital> findByUsuarioIdAndEstado(String usuarioId, EstadoMembresiaHospital estado);
    List<MembresiaHospital> findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(Long hospitalId);
    Optional<MembresiaHospital> findByUsuarioIdAndHospitalId(String usuarioId, Long hospitalId);
    long countByHospitalIdAndEstadoAndRolesContaining(Long hospitalId, EstadoMembresiaHospital estado, RolMembresiaHospital rol);
}
