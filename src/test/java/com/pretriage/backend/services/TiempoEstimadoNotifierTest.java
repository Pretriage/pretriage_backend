package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TiempoEstimadoNotifierTest {
    @Mock RepoConsultasMedicas repoConsultasMedicas;
    @Mock RepoEntradasCola repoEntradasCola;
    @Mock EstimacionAtencionService estimacionAtencionService;
    @InjectMocks TiempoEstimadoNotifier notifier;

    @Test
    void sigueConectadoCuandoLaConsultaYaFueLlamada() {
        EntradaCola entrada = entrada(EstadoEntradaCola.LLAMADO);
        autorizar(entrada);

        assertNotNull(notifier.conectar("auth", 5L));

        assertTrue(conexiones().containsKey(5L));
        verify(estimacionAtencionService, never()).calcularPara(any());
    }

    @Test
    void calculaLaEstimacionAlConectarseMientrasEstaEnCola() {
        EntradaCola entrada = entrada(EstadoEntradaCola.EN_COLA);
        autorizar(entrada);
        when(estimacionAtencionService.calcularPara(entrada.getConsultaMedica()))
                .thenReturn(new TiempoEstimadoAtencionResponse());

        notifier.conectar("auth", 5L);

        verify(estimacionAtencionService).calcularPara(entrada.getConsultaMedica());
    }

    @Test
    void noDejaAbiertoElCanalDeUnaConsultaCancelada() {
        autorizar(entrada(EstadoEntradaCola.CANCELADA));

        assertNotNull(notifier.conectar("auth", 5L));

        assertFalse(conexiones().containsKey(5L));
    }

    @Test
    void cierraElCanalCuandoSeAvisaQueElTurnoSeCancelo() {
        autorizar(entrada(EstadoEntradaCola.EN_ESPERA));
        notifier.conectar("auth", 5L);

        notifier.notificarCambioDeEstado(CambioEstadoConsultaEvento.desde(entrada(EstadoEntradaCola.CANCELADA)));

        assertFalse(conexiones().containsKey(5L));
    }

    @Test
    void rechazaConsultasDeOtroPaciente() {
        when(repoConsultasMedicas.existsByIdAndPacienteUsuarioAuthId(5L, "otro")).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> notifier.conectar("otro", 5L));
    }

    @Test
    void elEventoIncluyeDondeTieneQuePresentarseYElPlazo() {
        EntradaCola entrada = entrada(EstadoEntradaCola.EN_ESPERA);
        LocalDateTime limite = LocalDateTime.of(2026, 11, 14, 15, 40);
        entrada.setTipoPausa(TipoPausaCola.AUSENTE_AL_LLAMADO);
        entrada.setFechaHoraLimiteRespuesta(limite);

        CambioEstadoConsultaEvento evento = CambioEstadoConsultaEvento.desde(entrada);

        assertEquals("Hospital Demo", evento.nombreHospital());
        assertEquals("Guardia de Adultos", evento.nombreSector());
        assertEquals("Consultorio 1", evento.nombreSala());
        assertEquals(limite, evento.fechaHoraLimiteRespuesta());
        assertEquals(evento.id(), CambioEstadoConsultaEvento.desde(entrada).id());
        entrada.setFechaHoraLimiteRespuesta(limite.plusMinutes(30));
        assertNotEquals(evento.id(), CambioEstadoConsultaEvento.desde(entrada).id());
    }

    private void autorizar(EntradaCola entrada) {
        when(repoConsultasMedicas.existsByIdAndPacienteUsuarioAuthId(5L, "auth")).thenReturn(true);
        when(repoEntradasCola.findByConsultaMedicaId(5L)).thenReturn(Optional.of(entrada));
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ?> conexiones() {
        return (Map<Long, ?>) ReflectionTestUtils.getField(notifier, "conexiones");
    }

    private static EntradaCola entrada(EstadoEntradaCola estado) {
        Hospital hospital = new Hospital();
        hospital.setNombre("Hospital Demo");
        Sector sector = new Sector();
        sector.setNombre("Guardia de Adultos");
        Sala sala = new Sala();
        sala.setNombre("Consultorio 1");
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setId(5L);
        consulta.setHospital(hospital);
        consulta.setSector(sector);
        consulta.setSala(sala);
        EntradaCola entrada = new EntradaCola();
        entrada.setConsultaMedica(consulta);
        entrada.setEstado(estado);
        return entrada;
    }
}
