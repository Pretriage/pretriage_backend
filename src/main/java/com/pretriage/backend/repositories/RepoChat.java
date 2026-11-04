package com.pretriage.backend.repositories;

import com.pretriage.backend.model.chat.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoChat extends JpaRepository<Chat, Long> {
    Optional<Chat> findByIdAndPacienteUsuarioAuthId(Long id, String auth0Id);
    interface TextTurnSnapshot {
        com.pretriage.backend.model.chat.EstadoTurnoTexto getEstadoTurnoTexto();
        boolean getFinalizado();
    }

    @Query("select c.estadoTurnoTexto as estadoTurnoTexto, c.finalizado as finalizado from Chat c " +
            "where c.id = :id and c.paciente.usuarioAuth.id = :auth0Id")
    Optional<TextTurnSnapshot> findTextTurnSnapshot(@Param("id") Long id, @Param("auth0Id") String auth0Id);

    @Query(value = "select contenido from mensaje where chat_id = :chatId and solicitud_id = :solicitudId", nativeQuery = true)
    Optional<String> findTextTurnContent(@Param("chatId") Long chatId, @Param("solicitudId") String solicitudId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chat c where c.id = :id and c.paciente.usuarioAuth.id = :auth0Id")
    Optional<Chat> findForUpdate(@Param("id") Long id, @Param("auth0Id") String auth0Id);
    Optional<Chat> findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc(String auth0Id);

    Optional<Chat> findFirstByPacienteUsuarioAuthIdAndFinalizadoFalse(String auth0Id);

    List<Chat> findAllByPacienteUsuarioAuthIdAndFinalizadoFalse(String auth0Id);

    @Query("select c.id, c.paciente.usuarioAuth.id, c.clasificacionAutomaticaVoz from Chat c where c.finalizado = true " +
            "and c.recuperacionAutomaticaClasificacion = true and c.estadoClasificacion = :estado " +
            "order by c.id")
    List<Object[]> findAutomaticClassificationRecoveryCandidates(
            @Param("estado") com.pretriage.backend.model.chat.EstadoClasificacionChat estado);

    @Query("select c.id, c.paciente.usuarioAuth.id, c.solicitudTurnoTextoId from Chat c where c.estadoTurnoTexto = :estado " +
            "and c.solicitudTurnoTextoId is not null and (c.turnoTextoEnCurso = false " +
            "or c.turnoTextoEnCurso is null or c.turnoTextoIniciadoEn is null or c.turnoTextoIniciadoEn < :antesDe) " +
            "order by c.id")
    List<Object[]> findTextTurnRecoveryCandidates(
            @Param("estado") com.pretriage.backend.model.chat.EstadoTurnoTexto estado,
            @Param("antesDe") java.time.LocalDateTime antesDe);
}
