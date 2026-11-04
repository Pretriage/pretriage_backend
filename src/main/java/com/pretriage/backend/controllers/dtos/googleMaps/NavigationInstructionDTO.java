package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NavigationInstructionDTO {
    private String instructions;// instrucciones de navegación para el paso de la ruta
    // ejemplo: "Autobús en dirección a 116 (Rojo): Once"
}
