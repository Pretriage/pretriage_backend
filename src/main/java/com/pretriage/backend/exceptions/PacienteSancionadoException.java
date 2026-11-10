package com.pretriage.backend.exceptions;

import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;

public class PacienteSancionadoException extends RuntimeException {
    private final EstadoSancionPacienteDTO sancion;

    public PacienteSancionadoException(EstadoSancionPacienteDTO sancion) {
        super("Por declaraciones de síntomas inconsistentes, no podés anotarte en la cola desde la app. "
                + "Podés atenderte igualmente acercándote a la recepción del hospital.");
        this.sancion = sancion;
    }

    public EstadoSancionPacienteDTO getSancion() {
        return sancion;
    }
}
