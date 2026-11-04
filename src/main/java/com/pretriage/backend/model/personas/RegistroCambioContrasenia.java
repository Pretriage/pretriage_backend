package com.pretriage.backend.model.personas;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Campos comunes a todo registro de cambio de contraseña (token de
 * restablecimiento y bloqueo por intentos fallidos del cambio autenticado).
 * No es una entidad: cada subclase conserva su propia tabla.
 */
@Getter
@Setter
@NoArgsConstructor
@MappedSuperclass
public abstract class RegistroCambioContrasenia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario", referencedColumnName = "id", nullable = false)
    private UsuarioAuth usuario;

    @Column(nullable = false)
    private LocalDateTime fechaHoraCreacion;

    protected RegistroCambioContrasenia(UsuarioAuth usuario, LocalDateTime fechaHoraCreacion) {
        this.usuario = usuario;
        this.fechaHoraCreacion = fechaHoraCreacion;
    }
}
