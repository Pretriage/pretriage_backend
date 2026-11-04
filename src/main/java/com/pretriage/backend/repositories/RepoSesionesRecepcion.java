package com.pretriage.backend.repositories;

import com.pretriage.backend.model.recepcion.EstadoSesionRecepcion;
import com.pretriage.backend.model.recepcion.SesionRecepcion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoSesionesRecepcion extends JpaRepository<SesionRecepcion, Long> {
    boolean existsByRecepcionistaIdAndEstado(Long recepcionistaId, EstadoSesionRecepcion estado);
    Optional<SesionRecepcion> findByIdAndRecepcionistaUsuarioAuthId(Long id, String auth0Id);
    Optional<SesionRecepcion> findFirstByRecepcionistaUsuarioAuthIdAndEstado(String auth0Id, EstadoSesionRecepcion estado);
}
