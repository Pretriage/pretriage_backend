package com.pretriage.backend.exceptions;

public class RecepcionistaNoExisteException extends RuntimeException {
    public RecepcionistaNoExisteException() {
        super("Recepcionista no existe");
    }
}
