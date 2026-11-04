package com.pretriage.backend.exceptions;

import com.pretriage.backend.controllers.dtos.EstadoEntradaChat;

/** Raised when a voice interview cannot safely continue as a clinical triage. */
public class EntradaChatNoClinicaException extends IllegalArgumentException {
    private final EstadoEntradaChat estadoEntrada;

    public EntradaChatNoClinicaException(EstadoEntradaChat estadoEntrada) {
        super("La entrada no aporta un motivo clinico comprensible para continuar el triage");
        this.estadoEntrada = estadoEntrada;
    }

    public EstadoEntradaChat estadoEntrada() {
        return estadoEntrada;
    }
}
