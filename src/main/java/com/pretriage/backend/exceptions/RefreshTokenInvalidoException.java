package com.pretriage.backend.exceptions;

public class RefreshTokenInvalidoException extends RuntimeException {

    public RefreshTokenInvalidoException() {
        super("El refresh token es inválido o expiró. Iniciá sesión nuevamente.");
    }

    public RefreshTokenInvalidoException(String message) {
        super(message);
    }
}
