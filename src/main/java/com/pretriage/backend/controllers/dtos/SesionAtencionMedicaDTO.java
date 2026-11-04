package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SesionAtencionMedicaDTO {

    private Long id;

    private Long hospitalId;

    private String codigoEspecialidad;

    private Long sectorId;

    private String nombreSector;

    private Long salaId;

    private EstadoSesionMedica estado;
}
