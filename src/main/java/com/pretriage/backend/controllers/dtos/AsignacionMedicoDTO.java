package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AsignacionMedicoDTO {

    private Long hospitalId;

    private String nombreHospital;

    private String codigoEspecialidad;

    private String nombreEspecialidad;
}
