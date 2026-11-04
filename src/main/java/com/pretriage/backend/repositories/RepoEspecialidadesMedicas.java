package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoEspecialidadesMedicas extends JpaRepository<EspecialidadMedica, Long> {

    Optional<EspecialidadMedica> findByCodigo(String codigo);

    boolean existsByCodigo(String codigo);
}
