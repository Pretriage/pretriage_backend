package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.TipoAvisoSancion;
import java.time.Instant;

public record EstadoSancionPacienteDTO(
        boolean vigente,
        boolean permanente,
        Instant fechaFin,
        String avisoId,
        TipoAvisoSancion tipoAviso) {
}
