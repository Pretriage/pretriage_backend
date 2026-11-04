package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.Paciente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoPacientes extends JpaRepository<Paciente, Long> {
    Optional<Paciente> findByUsuarioAuthId(String auth0Id);
    Optional<Paciente> findByNumeroDocumentoOrUsuarioAuthNumeroDocumento(String numeroDocumento, String numeroDocumentoAuth);
}
