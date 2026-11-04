package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class HospitalCercanoDTO {

    private Long idHospital;

    private String placeId;

    private String nombre;

    private String direccion;

    private List<EspecialidadMedicaDTO> especialidades;

    private LocalTime tiempoEstimadoArriboMejorRuta;

    private int pacientesEnCola;

    private boolean disponible;

    private Long minutosEsperaEstimados;

    private LocalDateTime fechaHoraAtencionEstimada;

    private Double valoracionPromedio;

    private Integer cantidadValoraciones;

}
