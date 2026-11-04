package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.UsuarioAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoUsuariosAuth extends JpaRepository<UsuarioAuth, String> {
    Optional<UsuarioAuth> findByCorreoElectronicoIgnoreCase(String correoElectronico);
    boolean existsByCorreoElectronicoIgnoreCase(String correoElectronico);
    boolean existsByNumeroDocumento(String numeroDocumento);
}
