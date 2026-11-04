package com.pretriage.backend.exceptions;


/**
 * El pedido de cambio de contraseña autenticado no cumple las reglas del
 * negocio (contraseña nueva igual a la actual, contraseña actual
 * incorrecta). Se responde 400 con mensaje de error
 */
public class ContraseniaInvalidaException extends RuntimeException {


    public ContraseniaInvalidaException(String mensaje) {
        super(mensaje);   
    }
}
