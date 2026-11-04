package com.pretriage.backend.exceptions;

public class ObraSocialYaExisteException extends RuntimeException {
    public ObraSocialYaExisteException() {
        super("La obra social ya existe");
    }
}
