package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.personas.RolSistema;
import com.pretriage.backend.model.personas.TipoDocumento;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Es obligatorio ingresar tu nombre")
    private String nombre;

    @NotBlank(message = "Es obligatorio ingresar tu apellido")
    private String apellido;


    @NotBlank(message = "Es obligatorio ingresar tu numero de documento")
    private String numeroDocumento;

    @NotNull(message = "Es obligatorio ingresar el tipo de documento")
    private TipoDocumento tipoDocumento;

    private TipoUsuario tipoUsuario;

    private String matricula;

    @Email(message = "Es obligatorio ingresar el mail")
    private String email;


    @NotBlank(message = "Es obligatorio ingresar la contraseña")
    private String password;

    @NotNull(message = "Es obligatorio ingresar el rol del usuario")
    private RolSistema rol;
}
