package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.DatosClinicosConsultaDTO;
import com.pretriage.backend.model.consultas.EstudioClinico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoEstudiosClinicos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DatosClinicosConsultaService {

    private final AtencionMedicoService atencionMedicoService;
    private final RepoEstudiosClinicos repoEstudiosClinicos;
    private final EstudioClinicoService estudioClinicoService;

    @Transactional(readOnly = true)
    public DatosClinicosConsultaDTO obtenerDatos(String auth0Id, Long sesionId, Long consultaId) {
        Paciente paciente = atencionMedicoService.obtenerConsultaParaRevision(auth0Id, sesionId, consultaId)
                .getPaciente();
        List<DatosClinicosConsultaDTO.Estudio> estudios = repoEstudiosClinicos
                .findAllByPacienteAndActivoTrue(paciente).stream()
                .sorted(Comparator.comparing(EstudioClinico::getFechaSubida,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(estudio -> new DatosClinicosConsultaDTO.Estudio(
                        estudio.getId(), estudio.getNombreArchivo(), estudio.getTipoArchivo(),
                        estudio.getExtensionArchivo(), estudio.getDescripcion(), estudio.getFechaSubida(),
                        estudio.getTamanoArchivo()))
                .toList();
        return new DatosClinicosConsultaDTO(
                paciente.getFechaNacimiento(), paciente.getGeneroBiologico(),
                paciente.getGeneroConElQueSeIdentifica(), paciente.getPeso(), paciente.getAltura(), estudios);
    }

    @Transactional(readOnly = true)
    public EstudioClinicoService.Archivo obtenerArchivo(
            String auth0Id, Long sesionId, Long consultaId, Long estudioId) {
        Paciente paciente = atencionMedicoService.obtenerConsultaParaRevision(auth0Id, sesionId, consultaId)
                .getPaciente();
        return estudioClinicoService.obtenerArchivoDePaciente(paciente, estudioId);
    }
}
