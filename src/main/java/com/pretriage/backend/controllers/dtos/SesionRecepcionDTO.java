package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.recepcion.EstadoSesionRecepcion;
import java.time.LocalDateTime;

public record SesionRecepcionDTO(Long id, Long hospitalId, String hospitalNombre,
                                 EstadoSesionRecepcion estado, LocalDateTime fechaHoraInicio,
                                 LocalDateTime fechaHoraFin) {}
