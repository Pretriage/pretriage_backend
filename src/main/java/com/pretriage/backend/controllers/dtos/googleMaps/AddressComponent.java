package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AddressComponent {

    private String shortText;

    private String longText;

    /**
     * Tipos del componente.
     * Ej: ["country", "political"] o ["locality", "political"]
     */
    private List<String> types;
}
