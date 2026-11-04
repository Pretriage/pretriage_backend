package com.pretriage.backend.model.personas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
public class CambioContraseniaToken extends RegistroCambioContrasenia {

    @Column(nullable = false, unique = true, length = 128)
    private String token;

    @Column(nullable = false)
    private LocalDateTime fechaHoraExpiracion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoCambioContrasenia estado = EstadoCambioContrasenia.PENDIENTE;

    public CambioContraseniaToken(UsuarioAuth usuario, String token, LocalDateTime fechaHoraCreacion, LocalDateTime fechaHoraExpiracion) {
        super(usuario, fechaHoraCreacion);
        this.token = token;
        this.fechaHoraExpiracion = fechaHoraExpiracion;
        this.estado = EstadoCambioContrasenia.PENDIENTE;
    }

    public boolean expiro() {
        return LocalDateTime.now().isAfter(fechaHoraExpiracion);
    }

    public boolean esPendienteValido() {
        return estado == EstadoCambioContrasenia.PENDIENTE && !expiro();
    }
}
