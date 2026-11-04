package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.SalaDTO;
import com.pretriage.backend.controllers.dtos.SectorDTO;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalaServiceTest {

    @Mock
    private RepoSalas repoSalas;
    @Mock
    private RepoSectores repoSectores;

    @InjectMocks
    private SalaService salaService;

    @Test
    void obtieneSoloLasSalasActivasDelSectorYEespecialidadSolicitados() {
        Long hospitalId = 1L;
        Long sectorId = 5L;
        String codigoEspecialidad = "CLINICA_MEDICA";
        Sector sector = mock(Sector.class);
        when(repoSectores.findByIdAndHospitalId(sectorId, hospitalId)).thenReturn(Optional.of(sector));
        Sala sala = mock(Sala.class);
        when(sala.getId()).thenReturn(7L);
        when(sala.getNombre()).thenReturn("Consultorio 3");
        when(repoSalas.findBySectorIdAndEspecialidadCodigoAndActivaTrue(
                sectorId, codigoEspecialidad)).thenReturn(List.of(sala));

        List<SalaDTO> resultado = salaService.obtenerSalas(hospitalId, sectorId, codigoEspecialidad);

        assertEquals(1, resultado.size());
        assertEquals(7L, resultado.getFirst().getId());
        assertEquals("Consultorio 3", resultado.getFirst().getNombre());
    }

    @Test
    void noListaSalasDeUnSectorQueNoPerteneceAlHospital() {
        when(repoSectores.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> salaService.obtenerSalas(1L, 5L, "CLINICA_MEDICA"));
    }

    @Test
    void obtieneLosSectoresActivosDelHospitalYEspecialidadSolicitados() {
        Long hospitalId = 1L;
        String codigoEspecialidad = "CLINICA_MEDICA";
        Sector sector = mock(Sector.class);
        when(sector.getId()).thenReturn(5L);
        when(sector.getNombre()).thenReturn("Sector A");
        when(repoSectores.findByHospitalIdAndEspecialidadCodigoAndActivaTrueOrderByNombreAsc(
                hospitalId, codigoEspecialidad)).thenReturn(List.of(sector));

        List<SectorDTO> resultado = salaService.obtenerSectores(hospitalId, codigoEspecialidad);

        assertEquals(1, resultado.size());
        assertEquals(5L, resultado.getFirst().getId());
        assertEquals("Sector A", resultado.getFirst().getNombre());
    }

    @Test
    void obtieneLaSalaDentroDelHospitalIndicado() {
        Sala sala = mock(Sala.class);
        when(repoSalas.findByIdAndHospitalId(7L, 1L)).thenReturn(Optional.of(sala));

        Sala resultado = salaService.obtenerSala(7L, 1L);

        assertSame(sala, resultado);
    }
}