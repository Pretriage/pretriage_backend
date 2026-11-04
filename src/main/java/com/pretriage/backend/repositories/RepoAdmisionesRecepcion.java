package com.pretriage.backend.repositories;

import com.pretriage.backend.model.recepcion.AdmisionRecepcion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RepoAdmisionesRecepcion extends JpaRepository<AdmisionRecepcion, Long> {
    Optional<AdmisionRecepcion> findByConsultaMedicaId(Long consultaId);
    Optional<AdmisionRecepcion> findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(Long id, String auth0Id);
    boolean existsBySesionRecepcionIdAndEstadoIn(Long sesionId, Collection<com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion> estados);
    List<AdmisionRecepcion> findBySesionRecepcionIdAndEstadoInOrderByFechaHoraInicioAsc(
            Long sesionId, Collection<com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion> estados);
}
