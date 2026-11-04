package com.pretriage.backend.exceptions;

public class ChatFinalizadoException extends RuntimeException {
    public ChatFinalizadoException() {
        super("La sesión de triage ya finalizó");
    }
}
