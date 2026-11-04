package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.NoSePudoEstimarElHorarioDeAtencion;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstimacionAtencionServiceTest {

    @Mock
    private RepoEntradasCola repoEntradasCola;
    @Mock
    private RepoSesionesAtencionMedica repoSesionesAtencionMedica;

    @InjectMocks
    private EstimacionAtencionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "minutosPromedioAtencion", 10);
    }

    @Test
    void calculaTiempoDinamicoConDosMedicosActivos() {
        Hospital hospital = hospital(10L);
        EspecialidadMedica especialidad = especialidad(20L);
        GestorDeCola gestor = gestor(30L, hospital, especialidad);
        ConsultaMedica consultaPaciente = consulta(100L, hospital, especialidad);
        EntradaCola primera = entrada(1L, consulta(90L, hospital, especialidad), gestor, 5, 1);
        EntradaCola segunda = entrada(2L, consulta(91L, hospital, especialidad), gestor, 4, 2);
        EntradaCola paciente = entrada(3L, consultaPaciente, gestor, 3, 3);

        when(repoEntradasCola.findByConsultaMedicaId(consultaPaciente.getId())).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                gestor.getId(), EstadoEntradaCola.EN_COLA))
                .thenReturn(List.of(primera, segunda, paciente));
        when(repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndSectorIdAndEstado(
                hospital.getId(), especialidad.getId(), 5L, EstadoSesionMedica.ACTIVA))
                .thenReturn(2);

        LocalDateTime antes = LocalDateTime.now();
        TiempoEstimadoAtencionResponse response = service.calcularPara(consultaPaciente);
        LocalDateTime despues = LocalDateTime.now();

        assertTrue(response.isHayMedicosActivos());
        assertEquals(2, response.getMedicosActivos());
        assertEquals(2, response.getMedicosParaEstimacion());
        assertEquals(3, response.getPosicionEnCola());
        assertEquals(2, response.getPacientesAntes());
        assertEquals(10, response.getMinutosPromedioAtencion());
        assertEquals(5L, response.getSectorId());
        assertEquals("Cardio", response.getNombreSector());
        assertNull(response.getMensaje());
        assertFalse(response.getFechaHoraAtencionEstimada().isBefore(antes.plusMinutes(10)));
        assertFalse(response.getFechaHoraAtencionEstimada().isAfter(despues.plusMinutes(10)));
    }

    @Test
    void calculaConUnMedicoVirtualCuandoNoHayMedicosActivos() {
        Hospital hospital = hospital(10L);
        EspecialidadMedica especialidad = especialidad(20L);
        GestorDeCola gestor = gestor(30L, hospital, especialidad);
        ConsultaMedica consultaPaciente = consulta(100L, hospital, especialidad);
        EntradaCola primera = entrada(1L, consulta(90L, hospital, especialidad), gestor, 5, 1);
        EntradaCola segunda = entrada(2L, consulta(91L, hospital, especialidad), gestor, 4, 2);
        EntradaCola paciente = entrada(3L, consultaPaciente, gestor, 3, 3);

        when(repoEntradasCola.findByConsultaMedicaId(consultaPaciente.getId())).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                gestor.getId(), EstadoEntradaCola.EN_COLA))
                .thenReturn(List.of(primera, segunda, paciente));
        when(repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndSectorIdAndEstado(
                hospital.getId(), especialidad.getId(), 5L, EstadoSesionMedica.ACTIVA))
                .thenReturn(0);

        LocalDateTime antes = LocalDateTime.now();
        TiempoEstimadoAtencionResponse response = service.calcularPara(consultaPaciente);
        LocalDateTime despues = LocalDateTime.now();

        assertFalse(response.isHayMedicosActivos());
        assertEquals(0, response.getMedicosActivos());
        assertEquals(1, response.getMedicosParaEstimacion());
        assertEquals(2, response.getPacientesAntes());
        assertEquals(5L, response.getSectorId());
        assertEquals("Cardio", response.getNombreSector());
        assertNotNull(response.getMensaje());
        assertFalse(response.getFechaHoraAtencionEstimada().isBefore(antes.plusMinutes(20)));
        assertFalse(response.getFechaHoraAtencionEstimada().isAfter(despues.plusMinutes(20)));
    }

    @Test
    void noEstimaSiLaEntradaNoEstaEnCola() {
        Hospital hospital = hospital(10L);
        EspecialidadMedica especialidad = especialidad(20L);
        GestorDeCola gestor = gestor(30L, hospital, especialidad);
        ConsultaMedica consultaPaciente = consulta(100L, hospital, especialidad);
        EntradaCola entrada = entrada(3L, consultaPaciente, gestor, 3, 3);
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);

        when(repoEntradasCola.findByConsultaMedicaId(consultaPaciente.getId())).thenReturn(Optional.of(entrada));

        assertThrows(NoSePudoEstimarElHorarioDeAtencion.class, () -> service.calcularPara(consultaPaciente));
    }

    @Test
    void esperaNuevaConsultaSinColaYConMedicosDevuelveCeroMinutos() {
        when(repoEntradasCola.countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado(
                10L, 20L, EstadoEntradaCola.EN_COLA)).thenReturn(0L);
        when(repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndEstado(
                10L, 20L, EstadoSesionMedica.ACTIVA)).thenReturn(2);

        var calculo = service.calcularEsperaParaNuevaConsulta(10L, 20L);

        assertEquals(0, calculo.pacientesEnCola());
        assertEquals(0, calculo.minutosEspera());
        assertTrue(calculo.hayMedicosActivos());
        assertNotNull(calculo.fechaHoraAtencionEstimada());
    }

    @Test
    void esperaNuevaConsultaDividePacientesEntreMedicos() {
        when(repoEntradasCola.countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado(
                10L, 20L, EstadoEntradaCola.EN_COLA)).thenReturn(5L);
        when(repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndEstado(
                10L, 20L, EstadoSesionMedica.ACTIVA)).thenReturn(2);

        var calculo = service.calcularEsperaParaNuevaConsulta(10L, 20L);

        assertEquals(5, calculo.pacientesEnCola());
        assertEquals(20, calculo.minutosEspera());
        assertTrue(calculo.hayMedicosActivos());
    }

    @Test
    void esperaNuevaConsultaSinMedicosUsaUnMedicoVirtual() {
        when(repoEntradasCola.countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado(
                10L, 20L, EstadoEntradaCola.EN_COLA)).thenReturn(3L);
        when(repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndEstado(
                10L, 20L, EstadoSesionMedica.ACTIVA)).thenReturn(0);

        LocalDateTime antes = LocalDateTime.now();
        var calculo = service.calcularEsperaParaNuevaConsulta(10L, 20L);
        LocalDateTime despues = LocalDateTime.now();

        assertEquals(3, calculo.pacientesEnCola());
        assertEquals(30, calculo.minutosEspera());
        assertFalse(calculo.hayMedicosActivos());
        assertFalse(calculo.fechaHoraAtencionEstimada().isBefore(antes.plusMinutes(30)));
        assertFalse(calculo.fechaHoraAtencionEstimada().isAfter(despues.plusMinutes(30)));
    }

    private EntradaCola entrada(Long id, ConsultaMedica consulta, GestorDeCola gestor, int prioridad, long ordenRelativo) {
        EntradaCola entrada = new EntradaCola();
        entrada.setId(id);
        entrada.setConsultaMedica(consulta);
        entrada.setGestorDeCola(gestor);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        entrada.setPrioridad(prioridad);
        entrada.setOrdenRelativo(ordenRelativo);
        entrada.setFechaHoraIngreso(LocalDateTime.now().minusMinutes(ordenRelativo));
        return entrada;
    }

    private ConsultaMedica consulta(Long id, Hospital hospital, EspecialidadMedica especialidad) {
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setId(id);
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);
        Sector sector = new Sector();
        sector.setId(5L);
        sector.setNombre("Cardio");
        consulta.setSector(sector);
        return consulta;
    }

    private GestorDeCola gestor(Long id, Hospital hospital, EspecialidadMedica especialidad) {
        GestorDeCola gestor = new GestorDeCola();
        gestor.setId(id);
        gestor.setHospital(hospital);
        gestor.setEspecialidad(especialidad);
        return gestor;
    }

    private Hospital hospital(Long id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private EspecialidadMedica especialidad(Long id) {
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(id);
        return especialidad;
    }
}