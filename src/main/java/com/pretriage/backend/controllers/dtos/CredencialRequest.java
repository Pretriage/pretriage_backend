package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class CredencialRequest {

    @NotBlank(message = "El nombre de la obra social es obligatorio")
    @Pattern(
            regexp = "^[A-Za-zÁÉÍÓÚáéíóúÑñ ]+$",
            message = "El nombre de la obra social solo puede contener letras y espacios"
    )
    private String nombreObraSocial;

    @NotBlank(message = "El número de afiliado es obligatorio")
    @Pattern(
            regexp = "^\\d+$",
            message = "El número de afiliado solo puede contener números"
    )
    @Size(
            min = 6,
            max = 20,
            message = "El número de afiliado debe tener entre 6 y 20 caracteres"
    )
    private String numeroAfiliado;

    @NotBlank(message = "El plan es obligatorio")
    @Pattern(
            regexp = "^[A-Za-z0-9 ]+$",
            message = "El plan solo puede contener caracteres alfanuméricos"
    )
    private String plan;

    @NotNull( message= "Es obligatorio ingresar una fecha de vencimiento para la credencial")
    private LocalDate fechaVencimiento;

}
