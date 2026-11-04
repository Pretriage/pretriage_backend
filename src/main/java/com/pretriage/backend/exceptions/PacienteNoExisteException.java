package com.pretriage.backend.exceptions;

public class PacienteNoExisteException extends RuntimeException{

    public PacienteNoExisteException() {
        super("No existe el paciente solicitado");
    }
}
