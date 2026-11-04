package com.pretriage.backend.exceptions;

public class NoSePudoEstimarElHorarioDeAtencion extends RuntimeException{

    public NoSePudoEstimarElHorarioDeAtencion() {
        super("La consulta no pertenece al hospital asociado");
    }

}
