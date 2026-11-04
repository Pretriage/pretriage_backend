package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SalaDTO {

    private Long id;

    @NotBlank(message = "El nombre de la sala no puede ser nulo o vacío")
    private String nombre;
}
