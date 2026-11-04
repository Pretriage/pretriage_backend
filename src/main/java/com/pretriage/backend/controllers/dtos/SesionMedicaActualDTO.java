package com.pretriage.backend.controllers.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SesionMedicaActualDTO {
    private SesionAtencionMedicaDTO sesion;
    private ConsultaLlamadaDTO consultaActual;
}
