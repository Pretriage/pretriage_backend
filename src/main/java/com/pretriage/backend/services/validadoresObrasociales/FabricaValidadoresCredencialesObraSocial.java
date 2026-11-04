package com.pretriage.backend.services.validadoresObrasociales;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FabricaValidadoresCredencialesObraSocial {

    private final List<ValidadorCredencialObraSocial> validadores;

    public ValidadorCredencialObraSocial obtenerValidador(String nombreObraSocial) {
        return validadores.getFirst(); // MockValidadorCredencialObraSocial
        /*
         * //Descomentar para Implementacion real:
         * return validadores.stream()
         * .filter(validador -> validador.getObraSocial()
         * .equalsIgnoreCase(nombreObraSocial))
         * .findFirst()
         * .orElseThrow(() -> new ObraSocialSinValidadorException(nombreObraSocial));
         */
    }

}
