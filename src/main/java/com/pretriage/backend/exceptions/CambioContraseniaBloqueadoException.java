package com.pretriage.backend.exceptions;

/**
 * El usuario superó el máximo de intentos fallidos de cambio de contraseña
 * autenticado y el endpoint está bloqueado temporalmente (HTTP 423).
 */
public class CambioContraseniaBloqueadoException extends RuntimeException {

    private final transient long minutosRestantes;

    public CambioContraseniaBloqueadoException(long minutosRestantes) {
        super("El cambio de contraseña está bloqueado por intentos fallidos. "
                + "Podrás intentarlo nuevamente en " + minutosRestantes + " minuto(s).");
        this.minutosRestantes = minutosRestantes;
    }

    public long getMinutosRestantes() {
        return minutosRestantes;
    }
}
