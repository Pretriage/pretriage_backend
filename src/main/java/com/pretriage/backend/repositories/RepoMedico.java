package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.Medico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoMedico extends JpaRepository<Medico, Long> {

    Optional<Medico> findByUsuarioAuthId(String auth0Id);
}
