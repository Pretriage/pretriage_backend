package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.exceptions.CredencialInvalidaException;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.services.validadoresObrasociales.ValidadorCredencialObraSocial;
import org.springframework.stereotype.Service;

@Service
public class ValidacionCredencialObraSocialService {

    public void validarCredencialObraSocial(CredencialRequest credencialRequest, Paciente paciente,
            ValidadorCredencialObraSocial validador) {
        if(!validador.validar(credencialRequest, paciente)){
            throw new CredencialInvalidaException();
        }
    }

}