package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class TiempoEstimadoArriboHospitalResponse {

    private String transporte;
    private LocalTime tiempoEstimadoArribo;
    private Long idHospital;
    private Integer distanciaMetros;
    private String PolylineCode;

    private List<CombinacionRutasDTO> combinacionesLineas;
}
