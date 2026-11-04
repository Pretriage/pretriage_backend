package com.pretriage.backend.exceptions;

public class ProveedorIaException extends RuntimeException {
    private final boolean transitorio;

    public ProveedorIaException(Throwable cause) {
        this(cause, cause instanceof ProveedorIaException proveedor && proveedor.esTransitorio());
    }

    public ProveedorIaException(Throwable cause, boolean transitorio) {
        super("No fue posible obtener una respuesta del asistente de triage", cause);
        this.transitorio = transitorio;
    }

    public boolean esTransitorio() {
        return transitorio;
    }
}
