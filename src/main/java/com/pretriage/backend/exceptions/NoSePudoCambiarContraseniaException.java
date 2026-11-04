package com.pretriage.backend.exceptions;

public class NoSePudoCambiarContraseniaException extends RuntimeException {
    public NoSePudoCambiarContraseniaException(String message) {
        super(message);
    }

    public NoSePudoCambiarContraseniaException() {
        super("No se pudo cambiar la contraseña. Intentá nuevamente más tarde.");
    }
}
