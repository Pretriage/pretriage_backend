package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.BloqueoCambioContrasenia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoBloqueoCambioContrasenia extends JpaRepository<BloqueoCambioContrasenia, Long> {

    Optional<BloqueoCambioContrasenia> findByUsuarioId(String usuarioId);
}
