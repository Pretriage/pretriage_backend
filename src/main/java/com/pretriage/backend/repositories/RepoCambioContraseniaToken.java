package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.CambioContraseniaToken;
import com.pretriage.backend.model.personas.EstadoCambioContrasenia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepoCambioContraseniaToken extends JpaRepository<CambioContraseniaToken, Long> {

    Optional<CambioContraseniaToken> findByToken(String token);

    List<CambioContraseniaToken> findByUsuarioIdAndEstadoAndFechaHoraCreacionAfter(String usuarioId, EstadoCambioContrasenia estado, LocalDateTime desde);

    long countByUsuarioIdAndEstadoAndFechaHoraCreacionAfter(String usuarioId, EstadoCambioContrasenia estado, LocalDateTime desde);

    List<CambioContraseniaToken> findByUsuarioIdAndEstado(String usuarioId, EstadoCambioContrasenia estado);

    long countByUsuarioIdAndFechaHoraCreacionAfter(String usuarioId, LocalDateTime desde);
}
