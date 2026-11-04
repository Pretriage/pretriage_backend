package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IniciarSesionMedicaRequest {

    @NotNull
    private Long hospitalId;

    @NotBlank
    private String codigoEspecialidad;

    @NotNull
    private Long sectorId;

    @NotNull
    private Long salaId;
}
