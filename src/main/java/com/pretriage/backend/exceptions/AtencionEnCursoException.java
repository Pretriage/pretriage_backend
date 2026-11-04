package com.pretriage.backend.exceptions;

public class AtencionEnCursoException extends RuntimeException {
    public AtencionEnCursoException() {
        super("El paciente ya tiene una atencion en curso");
    }
}
