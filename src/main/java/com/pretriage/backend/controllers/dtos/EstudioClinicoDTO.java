package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class EstudioClinicoDTO {
    private Long id;
    private Long pacienteId;

    private String nombreArchivo;

    @NotBlank(message = "es obligatorio indicar al menos el tipo de archivo")
    private String tipoArchivo;

    private String extensionArchivo;

    private String descripcion;

    private LocalDateTime fechaSubida;

    private Long tamanoArchivo;

    private String rutaArchivo;
}
