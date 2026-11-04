package com.pretriage.backend.exceptions;

public class ObraSocialSinValidadorException extends RuntimeException {

    public ObraSocialSinValidadorException(String nombreObraSocial) {
        super("No se puede validar la credencial para la obra social " + nombreObraSocial);
    }
}