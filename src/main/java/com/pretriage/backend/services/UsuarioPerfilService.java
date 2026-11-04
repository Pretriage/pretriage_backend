package com.pretriage.backend.services;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.pretriage.backend.controllers.dtos.PerfilUsuarioDTO;
import com.pretriage.backend.exceptions.PacienteNoExisteException;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.personas.Paciente;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsuarioPerfilService {

    private final PacienteService pacienteService;

    @Transactional
    public PerfilUsuarioDTO obtenerPacientePerfil(String auth0Id) {
        Optional<Paciente> pacienteOpt = pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id);

        if (pacienteOpt.isEmpty()) {
            throw new PacienteNoExisteException();
        } else {

            Paciente paciente = pacienteOpt.get();
            Direccion direccion = paciente.getDireccion();

            return this.mapearPacienteADTO(paciente, direccion);
        }

    }

    @Transactional
    public PerfilUsuarioDTO actualizarPacientePerfil(String auth0Id, PerfilUsuarioDTO perfilRequest) {
        Optional<Paciente> pacienteOpt = pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id);

        if (pacienteOpt.isEmpty()) {
            throw new PacienteNoExisteException();
        } else {

            Paciente paciente = pacienteOpt.get();
            Direccion direccion = paciente.getDireccion();

            if (direccion == null) {
                direccion = new Direccion();
                paciente.setDireccion(direccion);
            }

            // Actualizar los campos del paciente y la dirección con los datos del DTO
            paciente.setNombre(perfilRequest.getNombre());
            paciente.setApellido(perfilRequest.getApellido());
            paciente.setTipoDocumento(perfilRequest.getTipoDocumento());
            paciente.setNumeroDocumento(perfilRequest.getNumeroDocumento());
            paciente.setFechaNacimiento(perfilRequest.getFechaNacimiento());
            paciente.setGeneroBiologico(perfilRequest.getGeneroBiologico());
            paciente.setGeneroConElQueSeIdentifica(perfilRequest.getGeneroConElQueSeIdentifica());
            paciente.setCorreoElectronico(perfilRequest.getEmail());
            paciente.setTelefono(perfilRequest.getTelefono());
            paciente.setPeso(perfilRequest.getPeso());
            paciente.setAltura(perfilRequest.getAlturaPersona());

            direccion.setCalle(perfilRequest.getCalle());
            direccion.setAltura(perfilRequest.getAlturaDireccion());
            direccion.setPiso(perfilRequest.getPiso());
            direccion.setCodigoPostal(perfilRequest.getCodigoPostal());
            direccion.setCiudad(perfilRequest.getCiudad());
            direccion.setProvincia(perfilRequest.getProvincia());

            log.info("actualizando el perfil del paciente:");
            log.info("Nombre: {}", paciente.getNombre());
            log.info("Apellido: {}", paciente.getApellido());
            log.info("Correo electrónico: {}", paciente.getCorreoElectronico());
            log.info("Teléfono: {}", paciente.getTelefono());
            log.info("Direccion: {} {} {} {} {}", direccion.getCalle(), direccion.getAltura(), direccion.getCiudad(),
                    direccion.getProvincia(), direccion.getCodigoPostal());
            log.info("Peso: {}", paciente.getPeso());
            log.info("Altura: {}", paciente.getAltura());
            log.info("Tipo de documento: {}", paciente.getTipoDocumento());
            log.info("Número de documento: {}", paciente.getNumeroDocumento());
            log.info("Fecha de nacimiento: {}", paciente.getFechaNacimiento());

            // Guardar los cambios en la base de datos
            Paciente updatedPaciente = pacienteService.actualizarPaciente(paciente);

            return this.mapearPacienteADTO(updatedPaciente, updatedPaciente.getDireccion());
        }
    }

    private PerfilUsuarioDTO mapearPacienteADTO(Paciente paciente, Direccion direccion) {
        PerfilUsuarioDTO dto = new PerfilUsuarioDTO(
                paciente.getNombre(),
                paciente.getApellido(),
                paciente.getTipoDocumento(),
                paciente.getNumeroDocumento(),
                paciente.getFechaNacimiento(),
                paciente.getGeneroBiologico(),
                paciente.getGeneroConElQueSeIdentifica(),
                paciente.getCorreoElectronico(),
                paciente.getTelefono(),
                null,
                null,
                null,
                null,
                null,
                null,
                paciente.getPeso(),
                paciente.getAltura());

        if (direccion != null) {
            dto.setCalle(direccion.getCalle());
            dto.setAlturaDireccion(direccion.getAltura());
            dto.setPiso(direccion.getPiso());
            dto.setCodigoPostal(direccion.getCodigoPostal());
            dto.setCiudad(direccion.getCiudad());
            dto.setProvincia(direccion.getProvincia());
        }

        return dto;
    }
}
