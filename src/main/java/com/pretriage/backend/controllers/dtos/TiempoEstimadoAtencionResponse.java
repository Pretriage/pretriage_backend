package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TiempoEstimadoAtencionResponse {

    private Long consultaId;

    private LocalDateTime fechaHoraAtencionEstimada;

    private boolean hayMedicosActivos;

    private int medicosActivos;

    private int medicosParaEstimacion;

    private int posicionEnCola;

    private int pacientesAntes;

    private int minutosPromedioAtencion;

    private Long sectorId;

    private String nombreSector;

    private String codigoSala;

    private String mensaje;
}
