package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @Email(message = "Es obligatorio ingresar el mail")
    private String email;


    @NotBlank(message = "Es obligatorio ingresar la contraseña")
    private String password;
}
