package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.SalaDTO;
import com.pretriage.backend.controllers.dtos.SectorDTO;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class SalaService {

    private final RepoSalas repoSalas;
    private final RepoSectores repoSectores;

    @Transactional(readOnly = true)
    public List<SectorDTO> obtenerSectores(Long hospitalId, String codigoEspecialidad) {
        return repoSectores.findByHospitalIdAndEspecialidadCodigoAndActivaTrueOrderByNombreAsc(hospitalId,
                codigoEspecialidad).stream()
                .map(sector -> new SectorDTO(sector.getId(), sector.getNombre()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SalaDTO> obtenerSalas(Long hospitalId, Long sectorId, String codigoEspecialidad) {
        obtenerSector(sectorId, hospitalId);
        return repoSalas.findBySectorIdAndEspecialidadCodigoAndActivaTrue(sectorId, codigoEspecialidad).stream()
                .map(this::mapearSala)
                .toList();
    }

    public Sector obtenerSector(Long sectorId, Long hospitalId) {
        return repoSectores.findByIdAndHospitalId(sectorId, hospitalId)
                .orElseThrow(() -> new NoSuchElementException("Sector inexistente en el hospital indicado"));
    }

    public Sala obtenerSala(Long salaId, Long hospitalId) {
        return repoSalas.findByIdAndHospitalId(salaId, hospitalId)
                .orElseThrow(() -> new NoSuchElementException("Sala inexistente en el hospital indicado"));
    }

    private SalaDTO mapearSala(Sala sala) {
        SalaDTO dto = new SalaDTO();
        dto.setId(sala.getId());
        dto.setNombre(sala.getNombre());
        return dto;
    }
}