package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.PacienteNoExisteException;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoPacientes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PacienteService {

    private final RepoPacientes repoPacientes;

    public Paciente obtenerPaciente(Long idPaciente) {
        Optional<Paciente> opPaciente = repoPacientes.findById(idPaciente);

        if (opPaciente.isEmpty()) {
            throw new PacienteNoExisteException();
        }

        return opPaciente.get();
    }

    public Optional<Paciente> obtenerPacienteConUsuarioAuthId(String idUsuario) {
        return repoPacientes.findByUsuarioAuthId(idUsuario);
    }

    public Paciente actualizarPaciente(Paciente paciente) {
        return repoPacientes.save(paciente);
    }
}
