package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AtencionMedicaDTO {
    private Long id;
    private Long consultaId;
    private Long sesionId;
    private Long pacienteId;
    private Long hospitalId;
    private String codigoEspecialidad;
    private Long salaId;
    private EstadoAtencionMedica estado;
    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFin;
}
