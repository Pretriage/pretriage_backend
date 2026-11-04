package com.pretriage.backend.services.validadoresObrasociales;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.model.personas.Paciente;
import org.springframework.stereotype.Service;

@Service
public class MockValidadorCredencialObraSocial implements ValidadorCredencialObraSocial {

    @Override
    public boolean validar(CredencialRequest credencial, Paciente paciente) {
        return true;
    }

    @Override
    public String getObraSocial() {
        return "OSDE";
    }
}