package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.CredencialProfesional;
import com.pretriage.backend.model.personas.TipoMatriculaProfesional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoCredencialesProfesionales extends JpaRepository<CredencialProfesional, Long> {
    boolean existsByNumeroAndTipoAndJurisdiccionIgnoreCase(
            String numero, TipoMatriculaProfesional tipo, String jurisdiccion);
}
