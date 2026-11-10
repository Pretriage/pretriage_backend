package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Hospital;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoHospitales extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findByPlaceId(String placeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hospital h where h.id = :hospitalId")
    Optional<Hospital> findByIdForUpdate(@Param("hospitalId") Long hospitalId);
    Optional<Hospital> findByIdAndRecepcionistasId(Long id, Long recepcionistaId);
    List<Hospital> findByRecepcionistasUsuarioAuthId(String auth0Id);

    List<Hospital> findByPlaceIdInAndEspecialidadesCodigo(Collection<String> placeIds, String codigoEspecialidad);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"especialidades", "direccion.coordenada"})
    List<Hospital> findDistinctByEspecialidadesCodigo(String codigoEspecialidad);
}
