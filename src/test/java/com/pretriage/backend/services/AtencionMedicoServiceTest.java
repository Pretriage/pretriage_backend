package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.ConsultaLlamadaDTO;
import com.pretriage.backend.controllers.dtos.EstudioClinicoDTO;
import com.pretriage.backend.controllers.dtos.SesionMedicaActualDTO;
import com.pretriage.backend.controllers.dtos.PretriajeConsultaDTO;
import com.pretriage.backend.controllers.dtos.RevisionPrioridadRequest;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.consultas.*;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoClasificacionChat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.repositories.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AtencionMedicoServiceTest {
        @Mock
        RepoMedico repoMedico;
        @Mock
        RepoHospitales repoHospitales;
        @Mock
        RepoEspecialidadesMedicas repoEspecialidadesMedicas;
        @Mock
        RepoAsignacionesMedicoHospital repoAsignacionesMedicoHospital;
        @Mock
        RepoSesionesAtencionMedica repoSesionesAtencionMedica;
        @Mock
        RepoGestoresDeColas repoGestoresDeColas;
        @Mock
        RepoEntradasCola repoEntradasCola;
        @Mock
        RepoConsultasMedicas repoConsultasMedicas;
        @Mock
        RepoAtencionesMedicas repoAtencionesMedicas;
        @Mock
        RepoRevisionesPrioridadConsulta repoRevisionesPrioridadConsulta;
        @Mock
        RepoAdmisionesRecepcion repoAdmisionesRecepcion;
        @Mock
        RepoEstudiosClinicos repoEstudiosClinicos;
        @Mock
        tools.jackson.databind.ObjectMapper objectMapper;
        @Mock
        PacienteService pacienteService;
        @InjectMocks
        AtencionMedicoService service;
        @Mock
        UsuariosService usuariosService;
        @Mock
        SalaService salaService;
        @Mock
        GestionDeArchivosService gestionDeArchivosService;
        @Mock
        EstudioClinicoService estudioClinicoService;

        @Test
        void pretriajeParcialDevuelveEstadoYCadenaLiteralParaElMedicoDeLaAtencion() {
                Medico medico = new Medico();
                medico.setId(10L);
                Sala sala = new Sala();
                sala.setId(30L);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(20L);
                sesion.setMedico(medico);
                sesion.setSala(sala);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                Chat chat = new Chat();
                chat.setEstadoClasificacion(EstadoClasificacionChat.PARCIAL);
                chat.setCierreParcial(true);
                chat.setMensajes(List.of(
                        new Mensaje("Me duele la cabeza", AutorMensaje.PACIENTE, null),
                        new Mensaje("¿Desde cuándo?", AutorMensaje.BOT, null)));
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(50L);
                consulta.setMedico(medico);
                consulta.setSala(sala);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
                consulta.setChat(chat);
                EntradaCola entrada = new EntradaCola();
                entrada.setEstado(EstadoEntradaCola.EN_ATENCION);
                entrada.setConsultaMedica(consulta);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(20L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola.findByConsultaMedicaId(50L)).thenReturn(Optional.of(entrada));

                PretriajeConsultaDTO resultado = service.obtenerPretriaje("auth0", 20L, 50L);

                assertEquals(EstadoClasificacionChat.PARCIAL, resultado.estadoClasificacion());
                assertEquals(true, resultado.cierreParcial());
                assertNull(resultado.resumenClinico());
                assertEquals(List.of("Me duele la cabeza", "¿Desde cuándo?"), resultado.mensajesConversacion()
                                .stream().map(com.pretriage.backend.controllers.dtos.MensajeDTO::contenido).toList());
                assertEquals(List.of("PACIENTE", "BOT"), resultado.mensajesConversacion()
                                .stream().map(com.pretriage.backend.controllers.dtos.MensajeDTO::autor).toList());
        }

        @Test
        void noPermitePausarUnaSesionConConsultaTomada() {
                Medico medico = new Medico();
                medico.setId(10L);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(20L);
                sesion.setMedico(medico);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(20L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola.existsByConsultaMedicaMedicoIdAndEstadoIn(
                                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.anyCollection()))
                                .thenReturn(true);

                assertThrows(IllegalStateException.class, () -> service.pausarSesion("auth0", 20L));
        }

        @Test
        void listaUnaConsultaEnColaAunqueTodaviaNoTengaSala() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                Paciente paciente = new Paciente();
                paciente.setId(4L);
                paciente.setNombre("Ana");
                paciente.setApellido("Pérez");
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(5L);
                consulta.setCodigoLlamado("A-005");
                consulta.setPaciente(paciente);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L))
                                .thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA))
                                .thenReturn(List.of(entrada));

                List<ConsultaLlamadaDTO> resultado = service.listarPacientesDisponibles("auth0", 6L, null);

                assertEquals(1, resultado.size());
                assertEquals(5L, resultado.getFirst().getConsultaId());
                assertEquals("Ana", resultado.getFirst().getNombrePaciente());
                assertEquals("Pérez", resultado.getFirst().getApellidoPaciente());
                assertEquals(EstadoConsulta.EN_COLA, resultado.getFirst().getEstadoConsulta());
                assertEquals(NivelDeGravedad.URGENTE, resultado.getFirst().getPrioridad());
                assertNull(resultado.getFirst().getSalaId());
                assertNull(resultado.getFirst().getNombreSala());
        }

        @Test
        void llamarProximoRevalidaSesionDespuesDeAdquirirLock() {
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(20L);
                sesion.setEstado(EstadoSesionMedica.FINALIZADA);
                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(20L, "auth0"))
                                .thenReturn(Optional.of(sesion));

                IllegalStateException error = assertThrows(IllegalStateException.class,
                                () -> service.llamarProximo("auth0", 20L));

                assertEquals("La sesion medica debe estar activa", error.getMessage());
                verifyNoInteractions(repoGestoresDeColas, repoEntradasCola, repoConsultasMedicas);
        }

        @Test
        void recuperaLaSesionYLaConsultaActualDelMedico() {
                Medico medico = new Medico();
                medico.setId(10L);
                Hospital hospital = new Hospital();
                hospital.setId(20L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setCodigo("CLINICA_MEDICA");
                Sector sector = new Sector();
                sector.setId(70L);
                sector.setNombre("Sector A");
                Sala sala = new Sala();
                sala.setId(30L);
                sala.setNombre("Consultorio 1");
                Paciente paciente = new Paciente();
                paciente.setId(40L);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(50L);
                consulta.setPaciente(paciente);
                consulta.setMedico(medico);
                consulta.setSala(sala);
                consulta.setEstadoConsulta(EstadoConsulta.LLAMADO);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(60L);
                sesion.setMedico(medico);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setSala(sala);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));
                when(repoSesionesAtencionMedica.findFirstByMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraInicioDesc(
                                "auth0", List.of(EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA)))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola
                                .findFirstByConsultaMedicaMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraLlamadoDesc(
                                                "auth0",
                                                List.of(EstadoEntradaCola.LLAMADO, EstadoEntradaCola.EN_ATENCION)))
                                .thenReturn(Optional.of(entrada));

                SesionMedicaActualDTO resultado = service.obtenerSesionActual("auth0");

                assertEquals(60L, resultado.getSesion().getId());
                assertEquals(EstadoSesionMedica.ACTIVA, resultado.getSesion().getEstado());
                assertEquals(50L, resultado.getConsultaActual().getConsultaId());
                assertEquals(EstadoConsulta.LLAMADO, resultado.getConsultaActual().getEstadoConsulta());
        }

        @Test
        void noMuestraLaConsultaActualCuandoLaEntradaFueCancelada() {
                Medico medico = new Medico();
                medico.setId(10L);
                Hospital hospital = new Hospital();
                hospital.setId(20L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setCodigo("CLINICA_MEDICA");
                Sector sector = new Sector();
                sector.setId(70L);
                sector.setNombre("Sector A");
                Sala sala = new Sala();
                sala.setId(30L);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(60L);
                sesion.setMedico(medico);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setSala(sala);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));
                when(repoSesionesAtencionMedica.findFirstByMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraInicioDesc(
                                "auth0", List.of(EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA)))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola
                                .findFirstByConsultaMedicaMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraLlamadoDesc(
                                                "auth0", List.of(EstadoEntradaCola.LLAMADO, EstadoEntradaCola.EN_ATENCION)))
                                .thenReturn(Optional.empty());

                SesionMedicaActualDTO resultado = service.obtenerSesionActual("auth0");

                assertEquals(60L, resultado.getSesion().getId());
                assertNull(resultado.getConsultaActual());
        }

        @Test
        void creaLaAtencionHistoricaCuandoElPacienteConfirmaPresencia() {
                Medico medico = new Medico();
                medico.setId(10L);
                Sala sala = new Sala();
                sala.setId(30L);
                sala.setNombre("Sala 1");
                Paciente paciente = new Paciente();
                paciente.setId(40L);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(50L);
                consulta.setMedico(medico);
                consulta.setSala(sala);
                consulta.setPaciente(paciente);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                entrada.setEstado(EstadoEntradaCola.LLAMADO);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(20L);
                sesion.setMedico(medico);
                sesion.setSala(sala);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);
                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(20L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola.findByConsultaMedicaId(50L)).thenReturn(Optional.of(entrada));

                service.confirmarPresente("auth0", 20L, 50L);

                org.mockito.ArgumentCaptor<AtencionMedica> captor = org.mockito.ArgumentCaptor
                                .forClass(AtencionMedica.class);
                verify(repoAtencionesMedicas).save(captor.capture());
                assertEquals(EstadoAtencionMedica.EN_CURSO, captor.getValue().getEstado());
                assertEquals(sesion, captor.getValue().getSesionAtencionMedica());
                assertEquals(consulta, captor.getValue().getConsultaMedica());
        }

        @Test
        void confirmaLaPrioridadEnUnSoloPasoYLaDejaAuditada() {
                ContextoAtencion contexto = contextoAtencion();
                when(repoRevisionesPrioridadConsulta.findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(50L))
                                .thenReturn(Optional.empty());
                when(repoAdmisionesRecepcion.findByConsultaMedicaId(50L)).thenReturn(Optional.empty());

                PretriajeConsultaDTO resultado = service.revisarPrioridad(
                                "auth0", 20L, 50L,
                                new RevisionPrioridadRequest(DecisionRevisionPrioridad.CONFIRMAR, null, null));

                assertEquals(EstadoRevisionPrioridad.CONFIRMADA, resultado.estadoRevision());
                assertEquals(NivelDeGravedad.URGENTE, contexto.consulta().getNivelDeGravedadMedico());
                var captor = org.mockito.ArgumentCaptor.forClass(RevisionPrioridadConsulta.class);
                verify(repoRevisionesPrioridadConsulta).save(captor.capture());
                assertEquals(DecisionRevisionPrioridad.CONFIRMAR, captor.getValue().getDecision());
                assertNull(captor.getValue().getMotivo());
        }

        @Test
        void corrigeLaPrioridadSinExigirMotivo() {
                ContextoAtencion contexto = contextoAtencion();
                when(repoRevisionesPrioridadConsulta.findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(50L))
                                .thenReturn(Optional.empty());
                when(repoAdmisionesRecepcion.findByConsultaMedicaId(50L)).thenReturn(Optional.empty());

                PretriajeConsultaDTO resultado = service.revisarPrioridad(
                                "auth0", 20L, 50L,
                                new RevisionPrioridadRequest(DecisionRevisionPrioridad.CORREGIR, NivelDeGravedad.NORMAL,
                                                " "));

                assertEquals(EstadoRevisionPrioridad.CORREGIDA, resultado.estadoRevision());
                assertEquals(NivelDeGravedad.NORMAL, contexto.consulta().getNivelDeGravedadMedico());
        }

        @Test
        void noPermiteCorregirConLaMismaPrioridadPreliminar() {
                contextoAtencion();

                assertThrows(IllegalArgumentException.class, () -> service.revisarPrioridad(
                                "auth0", 20L, 50L,
                                new RevisionPrioridadRequest(DecisionRevisionPrioridad.CORREGIR,
                                                NivelDeGravedad.URGENTE, null)));
                verify(repoRevisionesPrioridadConsulta, never()).save(any());
        }

        @Test
        void noDuplicaUnaRevisionIdentica() {
                ContextoAtencion contexto = contextoAtencion();
                contexto.consulta().setNivelDeGravedadMedico(NivelDeGravedad.NORMAL);
                RevisionPrioridadConsulta existente = new RevisionPrioridadConsulta();
                existente.setDecision(DecisionRevisionPrioridad.CORREGIR);
                existente.setPrioridadAnterior(NivelDeGravedad.URGENTE);
                existente.setPrioridadNueva(NivelDeGravedad.NORMAL);
                existente.setMotivo(null);
                when(repoRevisionesPrioridadConsulta.findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(50L))
                                .thenReturn(Optional.of(existente));
                when(repoAdmisionesRecepcion.findByConsultaMedicaId(50L)).thenReturn(Optional.empty());

                PretriajeConsultaDTO resultado = service.revisarPrioridad(
                                "auth0", 20L, 50L,
                                new RevisionPrioridadRequest(DecisionRevisionPrioridad.CORREGIR, NivelDeGravedad.NORMAL,
                                                null));

                assertEquals(EstadoRevisionPrioridad.CORREGIDA, resultado.estadoRevision());
                verify(repoRevisionesPrioridadConsulta, never()).save(any());
        }

        @Test
        void exigeRevisionAntesDeFinalizarLaAtencion() {
                contextoAtencion();
                when(repoRevisionesPrioridadConsulta.findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(50L))
                                .thenReturn(Optional.empty());

                assertThrows(ConflictoDeEstadoException.class,
                                () -> service.finalizarConsulta("auth0", 20L, 50L));
                verify(repoAtencionesMedicas, never()).save(any());
        }

        private ContextoAtencion contextoAtencion() {
                Medico medico = new Medico();
                medico.setId(10L);
                Sala sala = new Sala();
                sala.setId(30L);
                Paciente paciente = new Paciente();
                paciente.setId(40L);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(50L);
                consulta.setMedico(medico);
                consulta.setSala(sala);
                consulta.setPaciente(paciente);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
                consulta.setEstadoConsulta(EstadoConsulta.EN_ATENCION);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                entrada.setEstado(EstadoEntradaCola.EN_ATENCION);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(20L);
                sesion.setMedico(medico);
                sesion.setSala(sala);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);
                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(20L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoEntradasCola.findByConsultaMedicaId(50L)).thenReturn(Optional.of(entrada));
                return new ContextoAtencion(consulta);
        }

        private record ContextoAtencion(ConsultaMedica consulta) {
        }

        @Test
        void obtenerHistorialClinicoRetornaListaDeEstudioClinicoDTO() {
                Medico medico = mock(Medico.class);
                Long pacienteId = 1L;

                EstudioClinico estudioClinico1 = new EstudioClinico();
                estudioClinico1.setId(100L);
                estudioClinico1.setNombreArchivo("radiografia.pdf");

                EstudioClinicoDTO estudio1 = new EstudioClinicoDTO();
                estudio1.setId(100L);
                estudio1.setPacienteId(pacienteId);
                estudio1.setNombreArchivo("radiografia.pdf");

                EstudioClinico estudioClinico2 = new EstudioClinico();
                estudioClinico2.setId(101L);
                estudioClinico2.setNombreArchivo("analisis.pdf");

                EstudioClinicoDTO estudio2 = new EstudioClinicoDTO();
                estudio2.setId(101L);
                estudio2.setPacienteId(pacienteId);
                estudio2.setNombreArchivo("analisis.pdf");

                Paciente pacienteMock = new Paciente();
                pacienteMock.setId(pacienteId);
                pacienteMock.agregarEstudioClinico(estudioClinico1);
                pacienteMock.agregarEstudioClinico(estudioClinico2);

                estudioClinico1.setPaciente(pacienteMock);
                estudioClinico2.setPaciente(pacienteMock);

                when(pacienteService.obtenerPaciente(pacienteId))
                                .thenReturn(pacienteMock);

                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));

                List<EstudioClinicoDTO> resultado = service.obtenerHistorialClinico("auth0", pacienteId);

                verify(repoMedico).findByUsuarioAuthId("auth0");

                assertEquals(2, resultado.size());
                assertEquals(estudio1.getNombreArchivo(), resultado.getFirst().getNombreArchivo());
                assertEquals(estudio2.getNombreArchivo(), resultado.get(1).getNombreArchivo());
        }

        @Test
        void obtenerUltimosEstudiosClinicosDevuelveLosCincoMasRecientesActivos() {
                Long pacienteId = 1L;
                Medico medico = new Medico();
                medico.setId(10L);
                Paciente paciente = new Paciente();
                paciente.setId(pacienteId);

                EstudioClinico e1 = new EstudioClinico();
                e1.setId(1L);
                e1.setNombreArchivo("reciente.pdf");
                e1.setFechaSubida(LocalDateTime.now().minusDays(1));
                e1.setPaciente(paciente);
                EstudioClinico e2 = new EstudioClinico();
                e2.setId(2L);
                e2.setNombreArchivo("medio.pdf");
                e2.setFechaSubida(LocalDateTime.now().minusDays(2));
                e2.setPaciente(paciente);

                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));
                when(pacienteService.obtenerPaciente(pacienteId)).thenReturn(paciente);
                when(repoEstudiosClinicos.findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(eq(pacienteId),
                                any(Pageable.class)))
                                .thenReturn(List.of(e1, e2));

                List<EstudioClinicoDTO> resultado = service.obtenerUltimosEstudiosClinicos("auth0", pacienteId, 5);

                assertEquals(2, resultado.size());
                assertEquals("reciente.pdf", resultado.getFirst().getNombreArchivo());
                org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
                verify(repoEstudiosClinicos).findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(eq(pacienteId),
                                captor.capture());
                assertEquals(0, captor.getValue().getPageNumber());
                assertEquals(5, captor.getValue().getPageSize());
                verify(pacienteService).obtenerPaciente(pacienteId);
        }

        @Test
        void obtenerUltimosEstudiosClinicosDevuelveListaVaciaSiNoHayEstudios() {
                Long pacienteId = 2L;
                Medico medico = new Medico();
                medico.setId(11L);
                Paciente paciente = new Paciente();
                paciente.setId(pacienteId);
                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));
                when(pacienteService.obtenerPaciente(pacienteId)).thenReturn(paciente);
                when(repoEstudiosClinicos.findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(eq(pacienteId),
                                any(Pageable.class)))
                                .thenReturn(List.of());

                List<EstudioClinicoDTO> resultado = service.obtenerUltimosEstudiosClinicos("auth0", pacienteId, 5);

                assertEquals(0, resultado.size());
        }

        @Test
        void obtenerUltimosEstudiosClinicosExigeMedicoValido() {
                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.empty());

                assertThrows(AccessDeniedException.class,
                                () -> service.obtenerUltimosEstudiosClinicos("auth0", 99L, 5));
                verify(repoEstudiosClinicos, never()).findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(any(), any());
        }

        @Test
        void obtenerUltimosEstudiosClinicosRespetaElLimitePersonalizado() {
                Long pacienteId = 1L;
                Medico medico = new Medico();
                medico.setId(10L);
                Paciente paciente = new Paciente();
                paciente.setId(pacienteId);

                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));
                when(pacienteService.obtenerPaciente(pacienteId)).thenReturn(paciente);
                when(repoEstudiosClinicos.findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(eq(pacienteId),
                                any(Pageable.class)))
                                .thenReturn(List.of());

                service.obtenerUltimosEstudiosClinicos("auth0", pacienteId, 10);

                org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
                verify(repoEstudiosClinicos).findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(eq(pacienteId),
                                captor.capture());
                assertEquals(10, captor.getValue().getPageSize());
        }

        @Test
        void rechazaLimiteMenorAUno() {
                Long pacienteId = 1L;
                Medico medico = new Medico();
                medico.setId(10L);
                when(repoMedico.findByUsuarioAuthId("auth0")).thenReturn(Optional.of(medico));

                assertThrows(IllegalArgumentException.class,
                                () -> service.obtenerUltimosEstudiosClinicos("auth0", pacienteId, 0));
                assertThrows(IllegalArgumentException.class,
                                () -> service.obtenerUltimosEstudiosClinicos("auth0", pacienteId, -1));
                verify(repoEstudiosClinicos, never()).findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(any(), any());
        }

        // --- Filtro por DNI ---

        @Test
        void listarPacientesDisponibles_filtraPorDniExacto_conCoincidencia() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                Paciente paciente = new Paciente();
                paciente.setId(4L);
                paciente.setNombre("Juan");
                paciente.setApellido("Perez");
                paciente.setNumeroDocumento("30111222");
                paciente.setTipoDocumento(com.pretriage.backend.model.personas.TipoDocumento.DNI);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(5L);
                consulta.setCodigoLlamado("A-005");
                consulta.setPaciente(paciente);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L)).thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA, "30111222"))
                                .thenReturn(List.of(entrada));

                List<ConsultaLlamadaDTO> resultado = service.listarPacientesDisponibles("auth0", 6L, "30111222");

                assertEquals(1, resultado.size());
                assertEquals(5L, resultado.getFirst().getConsultaId());
                assertEquals("30111222", resultado.getFirst().getNumeroDocumento());
                assertEquals(com.pretriage.backend.model.personas.TipoDocumento.DNI,
                                resultado.getFirst().getTipoDocumento());
                assertEquals("Juan", resultado.getFirst().getNombrePaciente());
                assertEquals("Perez", resultado.getFirst().getApellidoPaciente());
                assertEquals(EstadoConsulta.EN_COLA, resultado.getFirst().getEstadoConsulta());
                verify(repoEntradasCola)
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA, "30111222");
                verify(repoEntradasCola, never())
                                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                any(), any());
        }

        @Test
        void listarPacientesDisponibles_filtraPorDniExacto_sinCoincidencia_retornaVacia() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L)).thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA, "99999999"))
                                .thenReturn(List.of());

                List<ConsultaLlamadaDTO> resultado = service.listarPacientesDisponibles("auth0", 6L, "99999999");

                assertEquals(0, resultado.size());
        }

        @Test
        void listarPacientesDisponibles_dniConEspacios_haceTrimYFiltra() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                Paciente paciente = new Paciente();
                paciente.setId(4L);
                paciente.setNombre("Maria");
                paciente.setApellido("Gomez");
                paciente.setNumeroDocumento("30111222");
                paciente.setTipoDocumento(com.pretriage.backend.model.personas.TipoDocumento.DNI);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(5L);
                consulta.setPaciente(paciente);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L)).thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA, "30111222"))
                                .thenReturn(List.of(entrada));

                List<ConsultaLlamadaDTO> resultado = service.listarPacientesDisponibles("auth0", 6L, "  30111222  ");

                assertEquals(1, resultado.size());
                verify(repoEntradasCola)
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA, "30111222");
        }

        @Test
        void listarPacientesDisponibles_dniBlankONull_retornaTodosSinFiltrar() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                Paciente paciente = new Paciente();
                paciente.setId(4L);
                paciente.setNombre("Ana");
                paciente.setApellido("Perez");
                paciente.setNumeroDocumento("30111222");
                paciente.setTipoDocumento(com.pretriage.backend.model.personas.TipoDocumento.DNI);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(5L);
                consulta.setPaciente(paciente);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L)).thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA))
                                .thenReturn(List.of(entrada));

                List<ConsultaLlamadaDTO> resultadoBlank = service.listarPacientesDisponibles("auth0", 6L, "   ");
                assertEquals(1, resultadoBlank.size());
                List<ConsultaLlamadaDTO> resultadoNull = service.listarPacientesDisponibles("auth0", 6L, null);
                assertEquals(1, resultadoNull.size());
                List<ConsultaLlamadaDTO> resultadoVacio = service.listarPacientesDisponibles("auth0", 6L, "");
                assertEquals(1, resultadoVacio.size());

                verify(repoEntradasCola, times(3))
                                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA);
                verify(repoEntradasCola, never())
                                .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                any(), any(), any());
        }

        @Test
        void listarPacientesDisponibles_mapeoIncluyeNumeroDocumentoYTipoDocumento() {
                Hospital hospital = new Hospital();
                hospital.setId(1L);
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(2L);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(3L);
                Sector sector = new Sector();
                sector.setId(7L);
                Paciente paciente = new Paciente();
                paciente.setId(4L);
                paciente.setNombre("Carlos");
                paciente.setApellido("Lopez");
                paciente.setNumeroDocumento("12345678");
                paciente.setTipoDocumento(com.pretriage.backend.model.personas.TipoDocumento.DNI);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setId(5L);
                consulta.setCodigoLlamado("A-010");
                consulta.setPaciente(paciente);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                consulta.setNivelDeGravedadBot(NivelDeGravedad.MUY_URGENTE);
                EntradaCola entrada = new EntradaCola();
                entrada.setConsultaMedica(consulta);
                SesionAtencionMedica sesion = new SesionAtencionMedica();
                sesion.setId(6L);
                sesion.setHospital(hospital);
                sesion.setEspecialidad(especialidad);
                sesion.setSector(sector);
                sesion.setEstado(EstadoSesionMedica.ACTIVA);

                when(repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(6L, "auth0"))
                                .thenReturn(Optional.of(sesion));
                when(repoGestoresDeColas.findByHospitalIdAndEspecialidadIdAndSectorId(1L, 2L, 7L)).thenReturn(Optional.of(gestor));
                when(repoEntradasCola
                                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                                                3L, EstadoEntradaCola.EN_COLA))
                                .thenReturn(List.of(entrada));

                List<ConsultaLlamadaDTO> resultado = service.listarPacientesDisponibles("auth0", 6L, null);

                assertEquals("12345678", resultado.getFirst().getNumeroDocumento());
                assertEquals(com.pretriage.backend.model.personas.TipoDocumento.DNI,
                                resultado.getFirst().getTipoDocumento());
                assertEquals("Carlos", resultado.getFirst().getNombrePaciente());
                assertEquals("Lopez", resultado.getFirst().getApellidoPaciente());
        }
}
