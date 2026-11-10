package com.pretriage.backend.repositories;

import com.pretriage.backend.model.recepcion.AdmisionRecepcion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RepoAdmisionesRecepcion extends JpaRepository<AdmisionRecepcion, Long> {
    Optional<AdmisionRecepcion> findByConsultaMedicaId(Long consultaId);
    @Query("""
            select a from AdmisionRecepcion a
            where a.id = :id and a.sesionRecepcion.recepcionista.usuarioAuth.id = :auth0Id
            and not exists (
                select m.id from MembresiaHospital m
                where m.usuario.id = :auth0Id and m.hospital = a.sesionRecepcion.hospital
                and (m.estado is null
                    or m.estado <> com.pretriage.backend.model.acceso.EstadoMembresiaHospital.ACTIVA
                    or com.pretriage.backend.model.acceso.RolMembresiaHospital.RECEPCIONISTA not member of m.roles
                    or com.pretriage.backend.model.acceso.RolMembresiaHospital.RECEPCIONISTA member of m.rolesSuspendidos)
            )
            """)
    Optional<AdmisionRecepcion> findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(
            @Param("id") Long id, @Param("auth0Id") String auth0Id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdmisionRecepcion a where a.id = :id and a.sesionRecepcion.recepcionista.usuarioAuth.id = :auth0Id")
    Optional<AdmisionRecepcion> findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(
            @Param("id") Long id, @Param("auth0Id") String auth0Id);
    boolean existsBySesionRecepcionIdAndEstadoIn(Long sesionId, Collection<com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion> estados);
    List<AdmisionRecepcion> findBySesionRecepcionIdAndEstadoInOrderByFechaHoraInicioAsc(
            Long sesionId, Collection<com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion> estados);
}
