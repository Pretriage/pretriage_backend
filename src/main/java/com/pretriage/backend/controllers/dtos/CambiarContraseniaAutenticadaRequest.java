package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Request del cambio de contraseña autenticado: siempre se piden la contraseña
 * actual y la nueva. Las reglas de fortaleza se expresan con anotaciones
 * estándar de Bean Validation sobre la contraseña nueva; al marcar el request
 * con {@code @Valid} en el controller, la validación se responde 400 antes de
 * invocar la lógica de cambio (los fallos de fortaleza NO cuentan como
 * intento fallido).
 */
@Getter
@Setter
public class CambiarContraseniaAutenticadaRequest {

    @NotBlank(message = "La contraseña actual es obligatoria")
    private String contraseniaActual;

    // 8-32 caracteres contados por punto de código Unicode (Java regex opera
    // sobre code points: un emoji o un par surrogado cuentan como 1)
    @NotBlank(message = "La nueva contraseña es obligatoria")
    @Pattern(regexp = "(?s).{8,32}",
            message = "La contraseña debe tener entre 8 y 32 caracteres")
    @Pattern(regexp = "(?s).*\\p{Lu}.*",
            message = "La contraseña debe contener al menos una letra mayúscula")
    @Pattern(regexp = "(?s).*\\p{Ll}.*",
            message = "La contraseña debe contener al menos una letra minúscula")
    // número (\p{Nd}) o especial (no letra, no dígito, no espacio)
    @Pattern(regexp = "(?s).*(\\p{Nd}|[^\\p{L}\\p{Nd}\\s]).*",
            message = "La contraseña debe contener al menos un número o carácter especial")
    private String contraseniaNueva;
}
