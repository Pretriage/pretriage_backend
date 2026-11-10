package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoDiscrepanciaSintomas;
import java.time.Instant;

public record DiscrepanciaAdminDTO(
        Long id,
        String pacienteNombre,
        String pacienteDni,
        String medicoNombre,
        Instant registradaEn,
        EstadoDiscrepanciaSintomas estado,
        EstadoSancionPacienteDTO sancion) {
}
