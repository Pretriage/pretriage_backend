package com.pretriage.backend.exceptions;

/**
 * JWT válido pero sin cuenta local (UsuarioAuth) correspondiente al sujeto del
 * token: divergencia entre Auth0 y la base local. Se responde 500 con mensaje
 * genérico, sin detalles internos (el log WARN explica la divergencia).
 */
public class UsuarioLocalNoExisteException extends RuntimeException {

    public UsuarioLocalNoExisteException(String auth0Id) {
        super("Ocurrió un error inesperado. Intentá nuevamente más tarde.");
    }
}
