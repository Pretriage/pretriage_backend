package com.pretriage.backend.exceptions;

public class ObraSocialNoExisteException extends RuntimeException {
    public ObraSocialNoExisteException() {
        super("La obra social no existe");
    }
}
