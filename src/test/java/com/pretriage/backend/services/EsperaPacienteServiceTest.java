package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoChat;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsperaPacienteServiceTest {
    @Mock PacienteService pacienteService;
    @Mock RepoEntradasCola repoEntradasCola;
    @Mock RepoConsultasMedicas repoConsultasMedicas;
    @Mock RepoChat repoChat;
    @Mock EntityManager entityManager;
    @Mock EstimacionAtencionService estimacionAtencionService;
    @InjectMocks EsperaPacienteService service;

    @org.junit.jupiter.api.BeforeEach
    void inyectarEntityManager() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Test
    void cancelaLaConsultaCuandoLaEsperaSuperaUnaHora() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ESPERA);
        EntradaCola entrada = new EntradaCola();
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        entrada.setConsultaMedica(consulta);
        entrada.setFechaHoraSalidaTemporal(LocalDateTime.now().minusMinutes(61));
        when(repoEntradasCola.findByEstadoAndFechaHoraSalidaTemporalBefore(
                any(EstadoEntradaCola.class), any(LocalDateTime.class))).thenReturn(List.of(entrada));

        when(repoConsultasMedicas.findByIdForUpdate(consulta.getId())).thenReturn(Optional.of(consulta));
        service.cancelarEsperasVencidas();

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
        verify(repoEntradasCola).save(entrada);
        verify(repoConsultasMedicas).save(consulta);
    }

    @Test
    void obtenerEstadoIncluyeSectorDelPaciente() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        Sector sector = new Sector(); sector.setId(8L); sector.setNombre("Guardia");
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setSector(sector); consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        EntradaCola entrada = new EntradaCola(); entrada.setId(1L); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        TiempoEstimadoAtencionResponse estimacion = new TiempoEstimadoAtencionResponse();
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdAndEstadoInOrderByIdDesc(eq(2L), any()))
                .thenReturn(Optional.of(entrada));
        when(estimacionAtencionService.calcularPara(consulta)).thenReturn(estimacion);

        var dto = service.obtenerEstado("auth");

        assertEquals(8L, dto.getSectorId());
        assertEquals("Guardia", dto.getNombreSector());
        assertSame(estimacion, dto.getTiempoEstimadoAtencion());
    }

    @Test
    void cancelaSeleccionDesdeEnCola() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        var dto = service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
        assertEquals(EstadoEntradaCola.CANCELADA, dto.getEstadoEntradaCola());
        assertEquals(EstadoConsulta.CANCELADA, dto.getEstadoConsulta());
        verify(repoEntradasCola).save(entrada);
        verify(repoConsultasMedicas).save(consulta);
    }

    @Test
    void rechazaCancelacionSeleccionDesdeLlamado() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.LLAMADO);
        consulta.setMedico(new Medico());
        consulta.setSala(new Sala());
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.LLAMADO);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        
        stubLocksForCancellation(consulta, entrada);
        assertThrows(ConflictoDeEstadoException.class, ()->{
            service.cancelarSeleccion("auth");
        });
       

        assertNotEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertNotEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
        assertNotNull(consulta.getMedico());
        assertNotNull(consulta.getSala());
    }

    @Test
    void cancelaSeleccionDesdeEnEspera() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ESPERA);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
    }

    @Test
    void cancelaSeleccionDesdeAtrasado() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.ATRASADO);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.ATRASADO);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
    }

    @Test
    void rechazaCancelacionConAtencionEnCurso() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ATENCION);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_ATENCION);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        assertThrows(ConflictoDeEstadoException.class, () -> service.cancelarSeleccion("auth"));
    }

    @Test
    void rechazaCancelacionDeConsultaFinalizada() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.FINALIZADA);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.FINALIZADA);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        assertThrows(ConflictoDeEstadoException.class, () -> service.cancelarSeleccion("auth"));
    }

    @Test
    void cancelaSeleccionEsIdempotente() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.CANCELADA);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.CANCELADA);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(consulta, entrada);

        var dto = service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, dto.getEstadoEntradaCola());
        verify(repoEntradasCola, never()).save(any(EntradaCola.class));
        verify(repoConsultasMedicas, never()).save(any(ConsultaMedica.class));
    }

    @Test
    void rechazaCancelacionSinSeleccionActiva() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.cancelarSeleccion("auth"));
    }

    @Test
    void cancelarSeleccionFinalizaChatAbierto() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        EntradaCola entrada = new EntradaCola(); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        Chat chat = new Chat(paciente);
        chat.setId(100L);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        when(repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth"))
                .thenReturn(Optional.of(chat));
        when(repoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse("auth")).thenReturn(List.of(chat));
        when(repoChat.findForUpdate(100L, "auth")).thenReturn(Optional.of(chat));
        stubLocksForCancellation(consulta, entrada);

        service.cancelarSeleccion("auth");

        assertTrue(chat.isFinalizado());
        verify(repoChat).save(chat);
    }

    @Test
    void cancelaLaSeleccionMasRecienteAunqueExistaHistorialCancelado() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica historial = new ConsultaMedica(); historial.setId(1L);
        historial.setEstadoConsulta(EstadoConsulta.CANCELADA);
        EntradaCola anterior = new EntradaCola(); anterior.setId(1L);
        anterior.setEstado(EstadoEntradaCola.CANCELADA);
        anterior.setConsultaMedica(historial);
        ConsultaMedica activa = new ConsultaMedica(); activa.setId(5L); activa.setPaciente(paciente);
        activa.setEstadoConsulta(EstadoConsulta.EN_ESPERA);
        EntradaCola entrada = new EntradaCola(); entrada.setId(9L);
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        entrada.setConsultaMedica(activa);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(activa, entrada);

        var dto = service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, activa.getEstadoConsulta());
        assertEquals(EstadoEntradaCola.CANCELADA, anterior.getEstado());
        assertEquals(EstadoEntradaCola.CANCELADA, dto.getEstadoEntradaCola());
        verify(repoEntradasCola).save(entrada);
        verify(repoConsultasMedicas).save(activa);
        verify(repoEntradasCola, times(2)).findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L);
        verify(repoEntradasCola, never()).save(anterior);
        verify(repoConsultasMedicas, never()).save(historial);
    }

    @Test
    void cancelaLaSeleccionActivaAunqueExistaHistorialFinalizado() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica historial = new ConsultaMedica(); historial.setId(3L);
        historial.setEstadoConsulta(EstadoConsulta.FINALIZADA);
        EntradaCola anterior = new EntradaCola(); anterior.setId(2L);
        anterior.setEstado(EstadoEntradaCola.FINALIZADA);
        anterior.setConsultaMedica(historial);
        ConsultaMedica activa = new ConsultaMedica(); activa.setId(5L); activa.setPaciente(paciente);
        activa.setEstadoConsulta(EstadoConsulta.EN_COLA);
        EntradaCola entrada = new EntradaCola(); entrada.setId(9L);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        entrada.setConsultaMedica(activa);
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        stubLocksForCancellation(activa, entrada);

        service.cancelarSeleccion("auth");

        assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
        assertEquals(EstadoConsulta.CANCELADA, activa.getEstadoConsulta());
        verify(repoEntradasCola).save(entrada);
        verify(repoConsultasMedicas).save(activa);
    }

    @Test
    void cancelacionEsperaClasificacionBloqueadaYNoResucitaLaCola() throws Exception {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.PRETRIAGE_EN_PROCESO);
        consulta.setNivelDeGravedadBot(com.pretriage.backend.model.consultas.NivelDeGravedad.NORMAL);
        EntradaCola entrada = new EntradaCola(); entrada.setId(9L);
        entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        Chat chat = new Chat(paciente); chat.setId(100L); chat.setFinalizado(true);

        ReentrantLock bloqueoChat = new ReentrantLock();
        ReentrantLock bloqueoConsulta = new ReentrantLock();
        CountDownLatch clasificacionTieneChat = new CountDownLatch(1);
        CountDownLatch terminarClasificacion = new CountDownLatch(1);
        AtomicReference<Throwable> errorClasificacion = new AtomicReference<>();
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc(2L))
                .thenReturn(Optional.of(entrada));
        when(repoChat.findFirstByPacienteUsuarioAuthIdOrderByFechaHoraCreacionDescIdDesc("auth"))
                .thenReturn(Optional.of(chat));
        when(repoChat.findForUpdate(100L, "auth")).thenAnswer(invocation -> {
            bloqueoChat.lock();
            return Optional.of(chat);
        });
        when(repoConsultasMedicas.findByIdForUpdate(5L)).thenAnswer(invocation -> {
            bloqueoConsulta.lock();
            return Optional.of(consulta);
        });
        when(repoEntradasCola.findByConsultaMedicaId(5L)).thenReturn(Optional.of(entrada));
        when(repoEntradasCola.save(entrada)).thenAnswer(invocation -> {
            if (bloqueoConsulta.isHeldByCurrentThread()) bloqueoConsulta.unlock();
            if (bloqueoChat.isHeldByCurrentThread()) bloqueoChat.unlock();
            return entrada;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var clasificacion = executor.submit(() -> {
                bloqueoChat.lock();
                clasificacionTieneChat.countDown();
                try {
                    if (!terminarClasificacion.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("La prueba no liberó la clasificación");
                    }
                    bloqueoConsulta.lock();
                    try {
                        consulta.setChat(chat);
                        consulta.setResumenPretriageJson("{}");
                        consulta.setNivelDeGravedadBot(com.pretriage.backend.model.consultas.NivelDeGravedad.URGENTE);
                        consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                        entrada.setEstado(EstadoEntradaCola.EN_COLA);
                        entrada.setPrioridad(4);
                    } finally {
                        bloqueoConsulta.unlock();
                    }
                } catch (Throwable error) {
                    errorClasificacion.set(error);
                } finally {
                    bloqueoChat.unlock();
                }
            });
            assertTrue(clasificacionTieneChat.await(5, TimeUnit.SECONDS));
            var cancelacion = executor.submit(() -> service.cancelarSeleccion("auth"));
            Thread.sleep(100);
            assertTrue(!cancelacion.isDone(), "la cancelación debe esperar el lock del chat");
            terminarClasificacion.countDown();

            clasificacion.get(5, TimeUnit.SECONDS);
            var respuesta = cancelacion.get(5, TimeUnit.SECONDS);
            if (errorClasificacion.get() != null) throw new AssertionError(errorClasificacion.get());

            assertEquals(EstadoEntradaCola.CANCELADA, entrada.getEstado());
            assertEquals(EstadoConsulta.CANCELADA, consulta.getEstadoConsulta());
            assertEquals(EstadoEntradaCola.CANCELADA, respuesta.getEstadoEntradaCola());
            assertEquals(4, entrada.getPrioridad());
            verify(repoChat).findForUpdate(100L, "auth");
            verify(repoConsultasMedicas).findByIdForUpdate(5L);
        } finally {
            terminarClasificacion.countDown();
            executor.shutdownNow();
        }
    }

    private void stubLocksForCancellation(ConsultaMedica consulta, EntradaCola entrada) {
        when(repoConsultasMedicas.findByIdForUpdate(consulta.getId())).thenReturn(Optional.of(consulta));
        when(repoEntradasCola.findByConsultaMedicaId(consulta.getId())).thenReturn(Optional.of(entrada));
    }
    private EntradaCola entradaAusente(Paciente paciente, LocalDateTime salida) {
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ESPERA);
        EntradaCola entrada = new EntradaCola(); entrada.setId(1L); entrada.setConsultaMedica(consulta);
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        entrada.setTipoPausa(TipoPausaCola.AUSENTE_AL_LLAMADO);
        entrada.setFechaHoraSalidaTemporal(salida);
        entrada.setFechaHoraLimiteRespuesta(salida.plusMinutes(60));
        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth")).thenReturn(Optional.of(paciente));
        when(repoEntradasCola.findFirstByConsultaMedicaPacienteIdAndEstadoInOrderByIdDesc(eq(2L), any()))
                .thenReturn(Optional.of(entrada));
        when(repoEntradasCola.save(entrada)).thenReturn(entrada);
        return entrada;
    }

    @Test
    void pedirMasTiempoSumaAlFinalDelPlazoYNoDesdeAhora() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        LocalDateTime salida = LocalDateTime.now().minusMinutes(10);
        EntradaCola entrada = entradaAusente(paciente, salida);
        LocalDateTime plazoInicial = entrada.getFechaHoraLimiteRespuesta();

        var dto = service.estoyAtrasado("auth");

        assertEquals(EstadoEntradaCola.ATRASADO, entrada.getEstado());
        assertEquals(plazoInicial.plusMinutes(EsperaPacienteService.MINUTOS_EXTENSION_ATRASO),
                entrada.getFechaHoraLimiteRespuesta());
        assertEquals(EsperaPacienteService.MAX_EXTENSIONES_ATRASO - 1, dto.getExtensionesRestantes());
    }

    @Test
    void sigoEnCaminoSumaAlPlazoVigenteHastaElMaximoPermitido() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        LocalDateTime salida = LocalDateTime.now().minusMinutes(10);
        EntradaCola entrada = entradaAusente(paciente, salida);
        entrada.setEstado(EstadoEntradaCola.ATRASADO);
        entrada.setTipoPausa(TipoPausaCola.ATRASADO_CONFIRMADO);
        entrada.setFechaHoraLimiteRespuesta(salida.plusMinutes(60 + EsperaPacienteService.MINUTOS_EXTENSION_ATRASO));
        LocalDateTime plazoVigente = entrada.getFechaHoraLimiteRespuesta();

        var dto = service.sigoAsistiendo("auth");

        assertEquals(plazoVigente.plusMinutes(EsperaPacienteService.MINUTOS_EXTENSION_ATRASO),
                entrada.getFechaHoraLimiteRespuesta());
        assertEquals(0, dto.getExtensionesRestantes());
        assertThrows(IllegalStateException.class, () -> service.sigoAsistiendo("auth"));
    }

    @Test
    void elPacienteAusenteAlLlamadoPuedeAvisarQueLlegoSinPedirMasTiempo() {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        EntradaCola entrada = entradaAusente(paciente, LocalDateTime.now().minusMinutes(5));
        entrada.setOrdenRelativo(10L);
        GestorDeCola gestor = new GestorDeCola(); gestor.setId(3L); entrada.setGestorDeCola(gestor);

        service.llegue("auth");

        assertEquals(EstadoEntradaCola.EN_COLA, entrada.getEstado());
        assertEquals(EstadoConsulta.EN_COLA, entrada.getConsultaMedica().getEstadoConsulta());
        assertEquals(null, entrada.getFechaHoraLimiteRespuesta());
    }
}
