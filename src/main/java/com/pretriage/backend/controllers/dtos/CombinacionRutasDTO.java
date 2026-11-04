package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CombinacionRutasDTO {

    private String nombreLinea;// puede ser null si no va en transporte publico

    private String tipoTransporte;
    private String indicaciones;

}
