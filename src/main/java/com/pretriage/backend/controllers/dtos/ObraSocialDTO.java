package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ObraSocialDTO {

    @NotBlank(message = "El nombre de la obra social es obligatorio")
    @Pattern(
            regexp = "^[A-Za-zÁÉÍÓÚáéíóúÑñ ]+$",
            message = "El nombre de la obra social solo puede contener letras y espacios"
    )
    private String nombre;
}
