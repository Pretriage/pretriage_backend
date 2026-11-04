package com.pretriage.backend.exceptions;

public class LimiteSolicitudesCambioContraseniaException extends RuntimeException {
    public LimiteSolicitudesCambioContraseniaException() {
        super("Superaste el límite de solicitudes de cambio de contraseña. Intentá nuevamente más tarde.");
    }
}
