package com.pretriage.backend.controllers.dtos.acceso;

import com.pretriage.backend.model.consultas.EstadoSesionMedica;

import java.time.LocalDateTime;

public record SesionMedicaAdminResponse(
        Long id,
        Long medicoId,
        String medicoNombre,
        EstadoSesionMedica estado,
        Long sectorId,
        String sectorNombre,
        Long salaId,
        String salaNombre,
        String codigoEspecialidad,
        String nombreEspecialidad,
        LocalDateTime fechaHoraInicio) {
}
