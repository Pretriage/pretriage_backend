package com.pretriage.backend.exceptions;

public class CredencialValidaYaExisteException extends RuntimeException{

    public CredencialValidaYaExisteException() {
        super("La credencial ya fue cargada anteriormente");
    }

}
