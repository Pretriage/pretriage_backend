package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion;

public record AdmisionRecepcionDTO(Long id, Long consultaId, Long pacienteId, String codigoLlamado,
                                   EstadoAdmisionRecepcion estado, NivelDeGravedad prioridad,
                                   Long sectorId, String nombreSector,
                                   TiempoEstimadoAtencionResponse estimacion) {}
