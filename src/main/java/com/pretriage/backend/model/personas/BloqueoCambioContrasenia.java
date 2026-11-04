package com.pretriage.backend.model.personas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Estado actual del bloqueo de cambio de contraseña autenticado: una fila por
 * usuario con el contador de intentos fallidos y, si se alcanzó el límite, la
 * fecha/hasta hasta la cual el endpoint queda bloqueado (no es un log
 * histórico).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "id_usuario"))
public class BloqueoCambioContrasenia extends RegistroCambioContrasenia {

    @Column(nullable = false)
    private int intentosFallidos = 0;

    private LocalDateTime bloqueadoHasta;

    public BloqueoCambioContrasenia(UsuarioAuth usuario, LocalDateTime fechaHoraCreacion) {
        super(usuario, fechaHoraCreacion);
    }

    public boolean estaBloqueado(LocalDateTime ahora) {
        return bloqueadoHasta != null && ahora.isBefore(bloqueadoHasta);
    }

    /**
     * Minutos enteros (redondeados hacia arriba) hasta el próximo intento
     * permitido; 0 si no hay bloqueo activo.
     */
    public long minutosRestantes(LocalDateTime ahora) {
        if (!estaBloqueado(ahora)) {
            return 0;
        }
        return (long) Math.ceil(Duration.between(ahora, bloqueadoHasta).toSeconds() / 60.0);
    }
}
