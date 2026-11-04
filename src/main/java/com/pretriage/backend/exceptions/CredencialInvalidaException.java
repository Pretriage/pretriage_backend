package com.pretriage.backend.exceptions;

public class CredencialInvalidaException extends RuntimeException {

    public CredencialInvalidaException() {
        super("La credencial de obra social no es válida");
    }

}