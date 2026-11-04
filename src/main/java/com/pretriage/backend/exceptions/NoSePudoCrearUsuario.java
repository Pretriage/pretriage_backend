package com.pretriage.backend.exceptions;

public class NoSePudoCrearUsuario extends RuntimeException{

    public NoSePudoCrearUsuario(){
        super("No se pudo crear el usuario, inténtelo nuevamente mas tarde");
    }

    public NoSePudoCrearUsuario(String message){
        super(message);
    }
}
