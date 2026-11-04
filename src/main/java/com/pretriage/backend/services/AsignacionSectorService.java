package com.pretriage.backend.services;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSectores;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class AsignacionSectorService {

    private final RepoSectores repoSectores;
    private final RepoEntradasCola repoEntradasCola;

    public Sector asignarSector(ConsultaMedica consultaMedica) {
        if (consultaMedica.getHospital() == null || consultaMedica.getEspecialidad() == null) {
            throw new IllegalArgumentException(
                    "La consulta debe tener hospital y especialidad para asignar un sector");
        }

        Long hospitalId = consultaMedica.getHospital().getId();
        Long especialidadId = consultaMedica.getEspecialidad().getId();
        List<Sector> sectores = repoSectores
                .findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(hospitalId, especialidadId);

        Sector sector = sectores.stream()
                .filter(this::tieneSalasActivas)
                .min(Comparator
                        .comparingLong((Sector s) -> repoEntradasCola
                                .countByGestorDeColaSectorIdAndEstado(s.getId(), EstadoEntradaCola.EN_COLA))
                        .thenComparing(Sector::getNombre))
                .orElseThrow(() -> new NoSuchElementException(
                        "No hay sectores disponibles para la especialidad seleccionada"));

        consultaMedica.setSector(sector);
        Paciente paciente = consultaMedica.getPaciente();
        if (paciente != null && !sector.getPacientesAsignados().contains(paciente)) {
            sector.getPacientesAsignados().add(paciente);
        }
        return sector;
    }

    private boolean tieneSalasActivas(Sector sector) {
        return sector.getSalas().stream().anyMatch(sala -> sala.isActiva());
    }
}