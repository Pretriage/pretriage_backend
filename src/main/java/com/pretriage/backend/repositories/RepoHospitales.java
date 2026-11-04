package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoHospitales extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByPlaceId(String placeId);
    Optional<Hospital> findByIdAndRecepcionistasId(Long id, Long recepcionistaId);
    List<Hospital> findByRecepcionistasUsuarioAuthId(String auth0Id);

    List<Hospital> findByPlaceIdInAndEspecialidadesCodigo(Collection<String> placeIds, String codigoEspecialidad);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"especialidades", "direccion.coordenada"})
    List<Hospital> findDistinctByEspecialidadesCodigo(String codigoEspecialidad);
}
