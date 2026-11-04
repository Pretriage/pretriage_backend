package com.pretriage.backend.exceptions;

public class TokenCambioContraseniaInvalidoException extends RuntimeException {
    public TokenCambioContraseniaInvalidoException() {
        super("el token no es válido o venció, por favor, solicite un nuevo token o revise si escribió bien el token");
    }

    public TokenCambioContraseniaInvalidoException(String message) {
        super(message);
    }
}
