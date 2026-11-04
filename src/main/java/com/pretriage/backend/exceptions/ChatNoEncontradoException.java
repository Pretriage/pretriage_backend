package com.pretriage.backend.exceptions;

public class ChatNoEncontradoException extends RuntimeException {
    public ChatNoEncontradoException() {
        super("Chat no encontrado");
    }
}
