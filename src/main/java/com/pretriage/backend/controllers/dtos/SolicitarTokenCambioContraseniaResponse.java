package com.pretriage.backend.controllers.dtos;

import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SolicitarTokenCambioContraseniaResponse {

    private String mensaje;

    private LocalTime tiempoExpiracion;
}
