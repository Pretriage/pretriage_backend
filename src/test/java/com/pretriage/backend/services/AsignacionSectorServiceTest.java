package com.pretriage.backend.services;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSectores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsignacionSectorServiceTest {

    @Mock
    private RepoSectores repoSectores;
    @Mock
    private RepoEntradasCola repoEntradasCola;

    @InjectMocks
    private AsignacionSectorService service;

    @Test
    void asignaElSectorActivoConMenosPacientesEnCola() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(2L);

        Sector saturado = sector(5L, "Sector A", hospital, especialidad, true);
        Sector libre = sector(6L, "Sector B", hospital, especialidad, true);

        when(repoSectores.findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(1L, 2L))
                .thenReturn(List.of(saturado, libre));
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(5L, EstadoEntradaCola.EN_COLA)).thenReturn(8L);
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(6L, EstadoEntradaCola.EN_COLA)).thenReturn(2L);

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);

        assertSame(libre, service.asignarSector(consulta));
        assertSame(libre, consulta.getSector());
    }

    @Test
    void descartaSectoresInactivosOSinSalasActivas() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(2L);

        Sector inactivo = sector(5L, "Sector A", hospital, especialidad, false);
        Sector sinSalasActivas = sectorConSala(6L, "Sector B", hospital, especialidad, false);
        Sector conSalasA = sector(7L, "Sector C", hospital, especialidad, true);
        Sector conSalasB = sector(8L, "Sector D", hospital, especialidad, true);

        when(repoSectores.findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(1L, 2L))
                .thenReturn(List.of(inactivo, sinSalasActivas, conSalasA, conSalasB));
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(7L, EstadoEntradaCola.EN_COLA)).thenReturn(2L);
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(8L, EstadoEntradaCola.EN_COLA)).thenReturn(1L);

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);

        assertSame(conSalasB, service.asignarSector(consulta));
    }

    @Test
    void desempataPorNombreCuandoHayIgualCantidadDePacientes() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(2L);

        Sector zeta = sector(5L, "Zona Z", hospital, especialidad, true);
        Sector alfa = sector(6L, "Ala A", hospital, especialidad, true);

        when(repoSectores.findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(1L, 2L))
                .thenReturn(List.of(zeta, alfa));
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(5L, EstadoEntradaCola.EN_COLA)).thenReturn(1L);
        when(repoEntradasCola.countByGestorDeColaSectorIdAndEstado(6L, EstadoEntradaCola.EN_COLA)).thenReturn(1L);

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);

        assertSame(alfa, service.asignarSector(consulta));
    }

    @Test
    void lanzaErrorSiNoHaySectoresConCapacidad() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(2L);

        when(repoSectores.findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(1L, 2L))
                .thenReturn(List.of());

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);

        assertThrows(NoSuchElementException.class, () -> service.asignarSector(consulta));
    }

    @Test
    void exigeHospitalYEspecialidad() {
        ConsultaMedica consulta = new ConsultaMedica();
        assertThrows(IllegalArgumentException.class, () -> service.asignarSector(consulta));

        consulta.setHospital(new Hospital());
        assertThrows(IllegalArgumentException.class, () -> service.asignarSector(consulta));
    }

    @Test
    void agregaUnaSolaVezElPacienteAlSectorAsignado() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(2L);
        Paciente paciente = new Paciente();
        paciente.setId(9L);

        Sector sector = sector(5L, "Sector A", hospital, especialidad, true);

        when(repoSectores.findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc(1L, 2L))
                .thenReturn(List.of(sector));

        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);
        consulta.setPaciente(paciente);

        service.asignarSector(consulta);
        service.asignarSector(consulta);

        assertEquals(1, sector.getPacientesAsignados().size());
        assertSame(paciente, sector.getPacientesAsignados().getFirst());
    }

    private Sector sector(Long id, String nombre, Hospital hospital, EspecialidadMedica especialidad,
            boolean conSalasActivas) {
        return sectorConSala(id, nombre, hospital, especialidad, conSalasActivas);
    }

    private Sector sectorConSala(Long id, String nombre, Hospital hospital, EspecialidadMedica especialidad,
            boolean salaActiva) {
        Sector sector = new Sector();
        sector.setId(id);
        sector.setNombre(nombre);
        sector.setHospital(hospital);
        sector.setEspecialidad(especialidad);
        Sala sala = mock(Sala.class);
        when(sala.isActiva()).thenReturn(salaActiva);
        sector.getSalas().add(sala);
        return sector;
    }
}