package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.personas.Genero;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DatosClinicosConsultaDTO(
        LocalDate fechaNacimiento,
        Genero generoBiologico,
        Genero generoConElQueSeIdentifica,
        Double peso,
        Integer altura,
        List<Estudio> estudios) {

    public record Estudio(
            Long id,
            String nombreArchivo,
            String tipoArchivo,
            String extensionArchivo,
            String descripcion,
            LocalDateTime fechaSubida,
            Long tamanoArchivo) {
    }
}
