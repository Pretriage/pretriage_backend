package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion;

import java.time.LocalDateTime;

public record AdmisionRecepcionDetalleDTO(
        Long id, Long consultaId, Long sesionId, Long pacienteId,
        String pacienteDni, String pacienteNombre, String pacienteApellido,
        Long hospitalId, String hospitalNombre,
        String especialidadCodigo, String especialidadNombre, String codigoLlamado,
        EstadoAdmisionRecepcion estado, NivelDeGravedad prioridad,
        LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFinalizacion,
        LocalDateTime fechaHoraCancelacion, TiempoEstimadoAtencionResponse estimacion) {
}
