package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.repositories.projections.AtencionEmbudoProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface RepoAtencionesMedicas extends JpaRepository<AtencionMedica, Long> {
    Optional<AtencionMedica> findByConsultaMedicaIdAndEstado(Long consultaId, EstadoAtencionMedica estado);
    Optional<AtencionMedica> findByConsultaMedicaIdAndSesionAtencionMedicaIdAndEstado(
            Long consultaId, Long sesionId, EstadoAtencionMedica estado);
    boolean existsBySesionAtencionMedicaIdAndEstado(Long sesionId, EstadoAtencionMedica estado);
    @Query("""
            select case when count(a) > 0 then true else false end
            from AtencionMedica a
            where a.consultaMedica.paciente.id = :pacienteId
              and a.sesionAtencionMedica.medico.id = :medicoId
              and not exists (
                  select m.id from MembresiaHospital m
                  where m.usuario.id = a.sesionAtencionMedica.medico.usuarioAuth.id
                    and m.hospital = a.sesionAtencionMedica.hospital
                    and (m.estado is null
                      or m.estado <> com.pretriage.backend.model.acceso.EstadoMembresiaHospital.ACTIVA
                      or com.pretriage.backend.model.acceso.RolMembresiaHospital.MEDICO not member of m.roles
                      or com.pretriage.backend.model.acceso.RolMembresiaHospital.MEDICO member of m.rolesSuspendidos)
              )
            """)
    boolean existsByConsultaMedicaPacienteIdAndSesionAtencionMedicaMedicoId(
            @Param("pacienteId") Long pacienteId, @Param("medicoId") Long medicoId);

    @Query("""
            select a
            from AtencionMedica a
            where a.sesionAtencionMedica.medico.usuarioAuth.id = :auth0Id
              and not exists (
                  select m.id from MembresiaHospital m
                  where m.usuario.id = :auth0Id and m.hospital = a.sesionAtencionMedica.hospital
                    and (m.estado is null
                      or m.estado <> com.pretriage.backend.model.acceso.EstadoMembresiaHospital.ACTIVA
                      or com.pretriage.backend.model.acceso.RolMembresiaHospital.MEDICO not member of m.roles
                      or com.pretriage.backend.model.acceso.RolMembresiaHospital.MEDICO member of m.rolesSuspendidos)
              )
            order by a.fechaHoraInicio desc
            """)
    List<AtencionMedica> findBySesionAtencionMedicaMedicoUsuarioAuthIdOrderByFechaHoraInicioDesc(
            @Param("auth0Id") String auth0Id);

    /**
     * Atenciones en el estado indicado (para métricas: FINALIZADA) cuya {@code EntradaCola}
     * pertenece al hospital y tiene {@code fechaHoraIngreso} en el rango {@code [desde, hasta)}.
     */
    @Query("""
            select new com.pretriage.backend.repositories.projections.AtencionEmbudoProjection(
                e.fechaHoraIngreso, a.fechaHoraInicio, a.fechaHoraFin)
            from EntradaCola e
            join AtencionMedica a on a.consultaMedica = e.consultaMedica
            where e.gestorDeCola.hospital.id = :hospitalId
              and e.fechaHoraIngreso >= :desde
              and e.fechaHoraIngreso < :hasta
              and a.estado = :estado
            """)
    List<AtencionEmbudoProjection> findAtencionesDelEmbudo(@Param("hospitalId") Long hospitalId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta,
            @Param("estado") EstadoAtencionMedica estado);
}
