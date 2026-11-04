package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SectorDTO {

    private Long id;

    private String nombre;

    public SectorDTO() {
    }

    public SectorDTO(Long id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }
}