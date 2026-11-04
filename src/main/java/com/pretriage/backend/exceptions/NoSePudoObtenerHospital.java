package com.pretriage.backend.exceptions;

public class NoSePudoObtenerHospital extends RuntimeException {
    public NoSePudoObtenerHospital() {
        super("No se pudo obtener la dirección del hospital seleccionado");
    }
}
