package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.Recepcionista;
import com.pretriage.backend.model.personas.UsuarioAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoRecepcionistas  extends JpaRepository<Recepcionista, Long> {
    boolean existsByUsuarioAuthId(String auth0Id);

    Optional<UsuarioAuth> findByUsuarioAuthId(String auth0Id);
    Optional<Recepcionista> findRecepcionistaByUsuarioAuthId(String auth0Id);
}
