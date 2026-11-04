package com.pretriage.backend.controllers.dtos;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HospitalSeleccionadoResponse {

    private Long idHospital;

    private String placeId;

    private String nombre;

    private String direccion;

    private Long sectorId;

    private String nombreSector;

    private List<SalaDTO> salas;

}