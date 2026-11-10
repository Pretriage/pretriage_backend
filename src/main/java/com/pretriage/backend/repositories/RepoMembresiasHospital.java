package com.pretriage.backend.repositories;

import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.MembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoMembresiasHospital extends JpaRepository<MembresiaHospital, Long> {
    List<MembresiaHospital> findByUsuarioIdAndEstado(String usuarioId, EstadoMembresiaHospital estado);
    List<MembresiaHospital> findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(Long hospitalId);
    Optional<MembresiaHospital> findByUsuarioIdAndHospitalId(String usuarioId, Long hospitalId);
    @Query("select count(m) from MembresiaHospital m where m.hospital.id = :hospitalId "
            + "and m.estado = :estado and :rol member of m.roles and :rol not member of m.rolesSuspendidos")
    long countByHospitalIdAndEstadoAndRolesContaining(@Param("hospitalId") Long hospitalId,
            @Param("estado") EstadoMembresiaHospital estado, @Param("rol") RolMembresiaHospital rol);
}
