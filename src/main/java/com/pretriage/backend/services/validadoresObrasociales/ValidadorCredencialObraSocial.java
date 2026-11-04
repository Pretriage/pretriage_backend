package com.pretriage.backend.services.validadoresObrasociales;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.model.personas.Paciente;

public interface ValidadorCredencialObraSocial {

    boolean validar(CredencialRequest credencial, Paciente paciente);

    String getObraSocial();
}
