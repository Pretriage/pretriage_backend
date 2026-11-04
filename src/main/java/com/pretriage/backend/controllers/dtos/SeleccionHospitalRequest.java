package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SeleccionHospitalRequest {

    @NotBlank(message = "Es obligatorio ingresar el id del hospital a seleccionar")
    private String placeId;

    @NotBlank(message = "Es obligatorio ingresar la especialidad medica")
    private String codigoEspecialidad;

}
