package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.ActualizarEstadoSalaRequest;
import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.ActualizarSectorRequest;
import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.GuardarSalaRequest;
import com.pretriage.backend.controllers.dtos.acceso.HospitalConfigurationDtos.GuardarSectorRequest;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoGestoresDeColas;
import com.pretriage.backend.repositories.RepoHospitales;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HospitalConfigurationServiceTest {
        @Mock
        StaffAccessService staffAccessService;
        @Mock
        RepoHospitales hospitales;
        @Mock
        RepoEspecialidadesMedicas especialidades;
        @Mock
        RepoSalas salas;
        @Mock
        RepoSectores sectores;
        @Mock
        RepoConsultasMedicas consultasMedicas;
        @Mock
        RepoEntradasCola entradasCola;
        @Mock
        RepoSesionesAtencionMedica sesionesAtencionMedica;
        @Mock
        RepoGestoresDeColas gestoresDeColas;
        @Mock
        RepoAuditoriasHospital auditorias;

        private HospitalConfigurationService service;
        private Hospital hospital;
        private EspecialidadMedica especialidad;
        private Sector sector;
        private UsuarioAuth actor;

        @BeforeEach
        void setUp() {
                service = new HospitalConfigurationService(staffAccessService, hospitales, especialidades, salas,
                                sectores,
                                consultasMedicas, entradasCola, sesionesAtencionMedica, gestoresDeColas, auditorias);
                hospital = new Hospital();
                hospital.setId(7L);
                hospital.setNombre("Hospital Escuela");
                especialidad = new EspecialidadMedica();
                especialidad.setId(4L);
                especialidad.setCodigo("CLINICA_MEDICA");
                especialidad.setNombre("Clínica médica");
                hospital.getEspecialidades().add(especialidad);
                sector = new Sector();
                sector.setId(2L);
                sector.setNombre("Sector Norte");
                sector.setActiva(true);
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                lenient().when(sectores.findByIdAndHospitalId(sector.getId(), hospital.getId())).thenReturn(Optional.of(sector));
                actor = new UsuarioAuth();
                actor.setId("auth0|admin");
                when(staffAccessService.exigirAdminHospital(actor.getId(), hospital.getId())).thenReturn(actor);
                lenient().when(hospitales.findById(hospital.getId())).thenReturn(Optional.of(hospital));
                lenient().when(hospitales.findByIdForUpdate(hospital.getId())).thenReturn(Optional.of(hospital));
        }

        @Test
        void creaUnaSalaParaUnaEspecialidadHabilitadaAsignadaAlSector() {
                when(salas.existsByHospitalIdAndSectorIdAndNombreIgnoreCase(hospital.getId(), sector.getId(),
                                "Consultorio 1")).thenReturn(false);
                when(salas.save(any(Sala.class))).thenAnswer(invocation -> {
                        Sala sala = invocation.getArgument(0);
                        sala.setId(12L);
                        return sala;
                });

                var response = service.crearSala(actor.getId(), hospital.getId(), sector.getId(),
                                new GuardarSalaRequest(" Consultorio 1 ", especialidad.getId()));

                assertEquals(12L, response.id());
                assertEquals("Consultorio 1", response.nombre());
                assertEquals(sector.getId(), response.sectorId());
                assertEquals(sector.getNombre(), response.sectorNombre());
                assertEquals(especialidad.getId(), response.especialidadId());
                assertTrue(response.activa());
                verify(auditorias).save(any());
        }

        @Test
        void noDeshabilitaUnaEspecialidadConSalasActivasEnCualquierSectorOSinSector() {
                when(especialidades.findById(especialidad.getId())).thenReturn(Optional.of(especialidad));
                when(salas.existsByHospitalIdAndEspecialidadIdAndActivaTrue(hospital.getId(),
                                especialidad.getId())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.deshabilitarEspecialidad(actor.getId(), hospital.getId(),
                                                especialidad.getId()));

                assertEquals("Desactivá las salas de la especialidad antes de quitarla del hospital",
                                error.getMessage());
                verify(hospitales, never()).save(any());
                verifyNoInteractions(sectores, auditorias);
                assertTrue(hospital.getEspecialidades().contains(especialidad));
        }

        @Test
        void deshabilitarEspecialidadSinSalasActivasNiSectores() {
                when(especialidades.findById(especialidad.getId())).thenReturn(Optional.of(especialidad));
                when(salas.existsByHospitalIdAndEspecialidadIdAndActivaTrue(hospital.getId(),
                                especialidad.getId())).thenReturn(false);
                when(especialidades.findAll()).thenReturn(java.util.List.of(especialidad));
                when(salas.findByHospitalIdOrderByNombreAsc(hospital.getId())).thenReturn(java.util.List.of());
                when(sectores.findByHospitalIdAndArchivadoFalseOrderByNombreAsc(hospital.getId())).thenReturn(java.util.List.of());

                var configuracion = service.deshabilitarEspecialidad(actor.getId(), hospital.getId(),
                                especialidad.getId());

                assertFalse(configuracion.especialidades().get(0).habilitada());
                verify(hospitales).save(hospital);
                verify(auditorias).save(any());
                verify(sectores, never()).findByIdAndHospitalId(any(), any());
                verify(especialidades, never()).deleteAllByIdInBatch(any());
                verify(salas, never()).deleteAllByIdInBatch(any());
                verify(salas, never()).save(any());
                verify(sectores, never()).deleteAllByIdInBatch(any());
                verify(sectores, never()).save(any());
        }

        @Test
        void deshabilitarEspecialidadRepetidaNoGuardaNiAuditaOtraVez() {
                when(especialidades.findById(especialidad.getId())).thenReturn(Optional.of(especialidad));
                when(especialidades.findAll()).thenReturn(java.util.List.of(especialidad));

                service.deshabilitarEspecialidad(actor.getId(), hospital.getId(), especialidad.getId());
                var repetida = service.deshabilitarEspecialidad(actor.getId(), hospital.getId(), especialidad.getId());

                assertFalse(repetida.especialidades().getFirst().habilitada());
                verify(hospitales).save(hospital);
                verify(auditorias).save(argThat(auditoria ->
                                "ESPECIALIDAD_DESHABILITADA".equals(auditoria.getAccion())
                                                && auditoria.getHospital().equals(hospital)
                                                && auditoria.getActor().equals(actor)));
                verify(salas).existsByHospitalIdAndEspecialidadIdAndActivaTrue(hospital.getId(), especialidad.getId());
        }

        @Test
        void bajaSoloConsultaSalasDelHospitalYEspecialidadSolicitadosYPreservaOtrasEspecialidades() {
                EspecialidadMedica otraEspecialidad = new EspecialidadMedica();
                otraEspecialidad.setId(99L);
                hospital.getEspecialidades().add(otraEspecialidad);
                when(especialidades.findById(especialidad.getId())).thenReturn(Optional.of(especialidad));
                // Other hospital/specialty combinations have active rooms. Only this pair is free.
                when(salas.existsByHospitalIdAndEspecialidadIdAndActivaTrue(anyLong(), anyLong()))
                                .thenAnswer(invocation -> !hospital.getId().equals(invocation.getArgument(0))
                                                || !especialidad.getId().equals(invocation.getArgument(1)));

                service.deshabilitarEspecialidad(actor.getId(), hospital.getId(), especialidad.getId());

                assertEquals(java.util.List.of(otraEspecialidad), hospital.getEspecialidades());
                verify(salas).existsByHospitalIdAndEspecialidadIdAndActivaTrue(hospital.getId(), especialidad.getId());
                verify(salas, never()).findBySectorId(any());
        }

        @Test
        void noCreaSalaSiNombreDuplicadoEnElSector() {
                when(salas.existsByHospitalIdAndSectorIdAndNombreIgnoreCase(hospital.getId(), sector.getId(),
                                "Consultorio 1")).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.crearSala(actor.getId(), hospital.getId(), sector.getId(),
                                                new GuardarSalaRequest("Consultorio 1", especialidad.getId())));

                assertEquals("Ya existe una sala con ese nombre en el sector", error.getMessage());
                verify(salas, never()).save(any());
        }

        @Test
        void noCreaSalaSiSectorNoExiste() {
                when(sectores.findByIdAndHospitalId(99L, hospital.getId())).thenReturn(Optional.empty());

                assertThrows(RecursoNoEncontradoException.class,
                                () -> service.crearSala(actor.getId(), hospital.getId(), 99L,
                                                new GuardarSalaRequest("Consultorio 1", especialidad.getId())));
        }

        @Test
        void noCreaSalaSiEspecialidadNoCoincideConLaDelSector() {
                EspecialidadMedica otra = new EspecialidadMedica();
                otra.setId(99L);
                otra.setCodigo("TRAUMATOLOGIA");
                otra.setNombre("Traumatología");
                hospital.getEspecialidades().add(otra);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.crearSala(actor.getId(), hospital.getId(), sector.getId(),
                                                new GuardarSalaRequest("Consultorio 1", otra.getId())));

                assertEquals("La especialidad de la sala debe coincidir con la especialidad del sector",
                                error.getMessage());
                verify(salas, never()).save(any());
        }

        @Test
        void actualizaSalaCambiaNombreYEspecialidad() {
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio Viejo");
                sala.setActiva(true);
                sala.setHospital(hospital);
                sala.setSector(sector);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));
                when(salas.existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot(hospital.getId(), "Consultorio 1",
                                sector.getId(), 12L)).thenReturn(false);
                when(salas.save(any(Sala.class))).thenAnswer(inv -> inv.getArgument(0));

                var response = service.actualizarSala(actor.getId(), hospital.getId(), sector.getId(), 12L,
                                new GuardarSalaRequest(" Consultorio 1 ", especialidad.getId()));

                assertEquals("Consultorio 1", response.nombre());
                assertEquals(sector.getId(), response.sectorId());
                assertEquals(sector.getNombre(), response.sectorNombre());
                verify(auditorias).save(any());
        }

        @Test
        void noActualizaSalaSiNombreDuplicadoEnElSector() {
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio Viejo");
                sala.setHospital(hospital);
                sala.setSector(sector);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));
                when(salas.existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot(hospital.getId(),
                                "Consultorio Duplicado", sector.getId(), 12L)).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarSala(actor.getId(), hospital.getId(), sector.getId(), 12L,
                                                new GuardarSalaRequest("Consultorio Duplicado",
                                                                especialidad.getId())));

                assertEquals("Ya existe una sala con ese nombre en el sector", error.getMessage());
                verify(salas, never()).save(any());
        }

        @Test
        void noActualizaSalaSiSalaNoPerteneceAlSector() {
                Sector otroSector = new Sector();
                otroSector.setId(55L);
                otroSector.setNombre("Sector Sur");
                otroSector.setHospital(hospital);
                otroSector.setEspecialidad(especialidad);
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio 1");
                sala.setHospital(hospital);
                sala.setSector(otroSector);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));

                assertThrows(RecursoNoEncontradoException.class,
                                () -> service.actualizarSala(actor.getId(), hospital.getId(), sector.getId(), 12L,
                                                new GuardarSalaRequest("Consultorio 1", especialidad.getId())));
        }

        @Test
        void noActualizaSalaSiSectorNoExiste() {
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio 1");
                sala.setHospital(hospital);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));
                when(sectores.findByIdAndHospitalId(99L, hospital.getId())).thenReturn(Optional.empty());

                assertThrows(RecursoNoEncontradoException.class,
                                () -> service.actualizarSala(actor.getId(), hospital.getId(), 99L, 12L,
                                                new GuardarSalaRequest("Consultorio 1", especialidad.getId())));
        }

        @Test
        void actualizaEstadoSalaActivaYDesactiva() {
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio 1");
                sala.setActiva(false);
                sala.setHospital(hospital);
                sala.setSector(sector);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));
                when(salas.save(any(Sala.class))).thenAnswer(inv -> inv.getArgument(0));

                var activa = service.actualizarEstadoSala(actor.getId(), hospital.getId(), sector.getId(), 12L,
                                new ActualizarEstadoSalaRequest(true));

                assertEquals(true, activa.activa());

                var desactiva = service.actualizarEstadoSala(actor.getId(), hospital.getId(), sector.getId(), 12L,
                                new ActualizarEstadoSalaRequest(false));

                assertEquals(false, desactiva.activa());
                verify(auditorias, times(2)).save(any());
        }

        @Test
        void noDesactivaSalaConSesionActivaOPausada() {
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio 1");
                sala.setHospital(hospital);
                sala.setSector(sector);
                sala.setEspecialidad(especialidad);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));
                when(sesionesAtencionMedica.existsBySalaIdAndEstadoIn(eq(12L), anyCollection())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarEstadoSala(actor.getId(), hospital.getId(), sector.getId(),
                                                12L, new ActualizarEstadoSalaRequest(false)));

                assertEquals("No se puede desactivar una sala con sesiones de atención activas o pausadas",
                                error.getMessage());
                verify(salas, never()).save(any());
        }

        @Test
        void noActivaSalaSiEspecialidadNoHabilitada() {
                EspecialidadMedica otra = new EspecialidadMedica();
                otra.setId(99L);
                otra.setCodigo("TRAUMATOLOGIA");
                otra.setNombre("Traumatología");
                Sala sala = new Sala();
                sala.setId(12L);
                sala.setNombre("Consultorio 1");
                sala.setActiva(false);
                sala.setHospital(hospital);
                sala.setSector(sector);
                sala.setEspecialidad(otra);
                when(salas.findByIdAndHospitalId(12L, hospital.getId())).thenReturn(Optional.of(sala));

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarEstadoSala(actor.getId(), hospital.getId(), sector.getId(),
                                                12L, new ActualizarEstadoSalaRequest(true)));

                assertEquals("La especialidad no está habilitada en el hospital", error.getMessage());
                verify(salas, never()).save(any());
        }

        @Test
        void creaUnSectorParaUnaEspecialidadHabilitada() {
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndArchivadoFalse(hospital.getId(), "Sector Norte"))
                                .thenReturn(false);
                when(sectores.save(any(Sector.class))).thenAnswer(invocation -> {
                        Sector sector = invocation.getArgument(0);
                        sector.setId(22L);
                        return sector;
                });

                var response = service.crearSector(actor.getId(), hospital.getId(),
                                new GuardarSectorRequest(" Sector Norte ", especialidad.getId()));

                assertEquals(22L, response.id());
                assertEquals("Sector Norte", response.nombre());
                assertEquals(especialidad.getId(), response.especialidadId());
                assertEquals(especialidad.getCodigo(), response.especialidadCodigo());
                assertEquals(especialidad.getNombre(), response.especialidadNombre());
                verify(auditorias).save(any());
        }

        @Test
        void noCreaSectorSiNombreDuplicadoEnHospital() {
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndArchivadoFalse(hospital.getId(), "Sector Norte")).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.crearSector(actor.getId(), hospital.getId(),
                                                new GuardarSectorRequest("Sector Norte", especialidad.getId())));

                assertEquals("Ya existe un sector con ese nombre en el hospital", error.getMessage());
                verify(sectores, never()).save(any());
        }

        @Test
        void noCreaSectorSiEspecialidadNoHabilitadaEnHospital() {
                EspecialidadMedica otra = new EspecialidadMedica();
                otra.setId(99L);
                otra.setCodigo("TRAUMATOLOGIA");
                otra.setNombre("Traumatología");

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.crearSector(actor.getId(), hospital.getId(),
                                                new GuardarSectorRequest("Sector Sur", otra.getId())));

                assertEquals("La especialidad no está habilitada en el hospital", error.getMessage());
                verify(sectores, never()).save(any());
        }

        @Test
        void obtenerIncluyeSectoresOrdenadosPorNombre() {
                Sector sector = new Sector();
                sector.setId(5L);
                sector.setNombre("Sector A");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(especialidades.findAll()).thenReturn(java.util.List.of(especialidad));
                when(salas.findByHospitalIdOrderByNombreAsc(hospital.getId())).thenReturn(java.util.List.of());
                when(sectores.findByHospitalIdAndArchivadoFalseOrderByNombreAsc(hospital.getId())).thenReturn(java.util.List.of(sector));

                var configuracion = service.obtener(actor.getId(), hospital.getId());

                assertEquals(1, configuracion.sectores().size());
                assertEquals("Sector A", configuracion.sectores().get(0).nombre());
        }

        @Test
        void creaUnSectorConActivaTruePorDefecto() {
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndArchivadoFalse(hospital.getId(), "Sector Nuevo"))
                                .thenReturn(false);
                when(sectores.save(any(Sector.class))).thenAnswer(invocation -> {
                        Sector sector = invocation.getArgument(0);
                        sector.setId(30L);
                        return sector;
                });

                var response = service.crearSector(actor.getId(), hospital.getId(),
                                new GuardarSectorRequest("Sector Nuevo", especialidad.getId()));

                assertTrue(response.activa());
        }

        @Test
        void actualizaSectorCambiaNombreEspecialidadYActiva() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Viejo");
                sector.setActiva(true);
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalId(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndIdNotAndArchivadoFalse(hospital.getId(), "Sector Nuevo", 22L))
                                .thenReturn(false);
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(sectores.save(any(Sector.class))).thenAnswer(inv -> inv.getArgument(0));

                var response = service.actualizarSector(actor.getId(), hospital.getId(), 22L,
                                new ActualizarSectorRequest(" Sector Nuevo ", especialidad.getId(), false));

                assertEquals("Sector Nuevo", response.nombre());
                assertEquals(false, response.activa());
                verify(auditorias).save(any());
        }

        @Test
        void desactivaSectorCuandoSuEspecialidadYaNoEstaHabilitada() {
                hospital.getEspecialidades().clear();
                when(sectores.save(sector)).thenReturn(sector);

                var response = service.actualizarSector(actor.getId(), hospital.getId(), sector.getId(),
                                new ActualizarSectorRequest(sector.getNombre(), especialidad.getId(), false));

                assertFalse(response.activa());
                assertEquals(especialidad.getId(), response.especialidadId());
        }

        @Test
        void noActualizaSectorSiNombreDuplicado() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Viejo");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalId(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndIdNotAndArchivadoFalse(hospital.getId(), "Sector Duplicado", 22L))
                                .thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarSector(actor.getId(), hospital.getId(), 22L,
                                                new ActualizarSectorRequest("Sector Duplicado", especialidad.getId(),
                                                                true)));

                assertEquals("Ya existe un sector con ese nombre en el hospital", error.getMessage());
                verify(sectores, never()).save(any());
        }

        @Test
        void noActualizaSectorSiTienePacientesEnSalas() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Viejo");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                Sala sala = new Sala();
                sala.setId(100L);
                sala.setNombre("Sala 1");
                when(sectores.findByIdAndHospitalId(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndIdNotAndArchivadoFalse(hospital.getId(), "Sector Nuevo", 22L))
                                .thenReturn(false);
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of(sala));
                when(consultasMedicas.existsBySalaIdInAndEstadoConsultaNotIn(anyCollection(), anyCollection()))
                                .thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarSector(actor.getId(), hospital.getId(), 22L,
                                                new ActualizarSectorRequest("Sector Nuevo", especialidad.getId(),
                                                                true)));

                assertEquals("No se puede modificar o eliminar un sector con pacientes pendientes o en atención",
                                error.getMessage());
        }

        @Test
        void noDesactivaSectorConSesionPausadaAunqueNoTengaSala() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalId(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(sectores.existsByHospitalIdAndNombreIgnoreCaseAndIdNotAndArchivadoFalse(hospital.getId(), "Sector Norte", 22L))
                                .thenReturn(false);
                when(sesionesAtencionMedica.existsBySectorIdAndEstadoIn(eq(22L), anyCollection())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarSector(actor.getId(), hospital.getId(), 22L,
                                                new ActualizarSectorRequest("Sector Norte", especialidad.getId(), false)));

                assertEquals("No se puede modificar o eliminar un sector con sesiones de atención activas o pausadas",
                                error.getMessage());
                verify(sectores, never()).save(any());
        }

        @Test
        void noActualizaSectorSiEspecialidadNoHabilitada() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Viejo");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                EspecialidadMedica otra = new EspecialidadMedica();
                otra.setId(99L);
                otra.setCodigo("TRAUMATOLOGIA");
                otra.setNombre("Traumatología");
                when(sectores.findByIdAndHospitalId(22L, hospital.getId())).thenReturn(Optional.of(sector));

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.actualizarSector(actor.getId(), hospital.getId(), 22L,
                                                new ActualizarSectorRequest("Sector Nuevo", otra.getId(), true)));

                assertEquals("La especialidad no está habilitada en el hospital", error.getMessage());
        }

        @Test
        void eliminaSectorSinPacientesNiSesiones() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                verify(sectores).deleteAllByIdInBatch(List.of(sector.getId()));
                verify(auditorias).save(any());
        }

        @Test
        void eliminaSectorYGestorVacioAunqueCambioLaEspecialidadDelSector() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Nuevo");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(33L);
                gestor.setSector(sector);
                EspecialidadMedica especialidadAnterior = new EspecialidadMedica();
                especialidadAnterior.setId(3L);
                especialidadAnterior.setNombre("Especialidad anterior");
                gestor.setEspecialidad(especialidadAnterior);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(gestoresDeColas.findBySectorId(22L)).thenReturn(Optional.of(gestor));

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                verify(gestoresDeColas).deleteAllByIdInBatch(List.of(gestor.getId()));
                verify(sectores).deleteAllByIdInBatch(List.of(sector.getId()));
        }

        @Test
        void noEliminaSectorSiConsultaActivaTieneSectorPeroNoSala() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(consultasMedicas.existsBySectorIdAndEstadoConsultaNotIn(eq(22L), anyCollection())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 22L));

                assertEquals("No se puede modificar o eliminar un sector con pacientes pendientes o en atención",
                                error.getMessage());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void archivaSectorConHistorialDeConsultasAunqueLaConsultaEsteFinalizada() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(consultasMedicas.existsBySectorId(22L)).thenReturn(true);

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                assertTrue(sector.isArchivado());
                assertFalse(sector.isActiva());
                verify(sectores).save(sector);
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void archivaSectorConSesionFinalizadaEnSuHistorial() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(sesionesAtencionMedica.existsBySectorId(22L)).thenReturn(true);

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                assertTrue(sector.isArchivado());
                assertFalse(sector.isActiva());
                verify(sectores).save(sector);
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void archivaSectorConEntradasHistoricasEnSuGestor() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(33L);
                gestor.getEntradas().add(new EntradaCola());
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(gestoresDeColas.findBySectorId(22L)).thenReturn(Optional.of(gestor));
                when(entradasCola.existsByGestorDeColaId(33L)).thenReturn(true);

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                assertTrue(sector.isArchivado());
                assertFalse(sector.isActiva());
                verify(sectores).save(sector);
                verify(gestoresDeColas, never()).deleteAllByIdInBatch(any());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void noArchivaSectorConEntradasDeColaActivasEnGestorLegado() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                GestorDeCola gestor = new GestorDeCola();
                gestor.setId(33L);
                gestor.setSector(sector);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(gestoresDeColas.findBySectorId(22L)).thenReturn(Optional.of(gestor));
                when(entradasCola.existsByGestorDeColaIdAndEstadoNotIn(eq(33L), anyCollection())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 22L));

                assertEquals("El sector todavía tiene pacientes pendientes en la cola", error.getMessage());
                assertFalse(sector.isArchivado());
                verify(sectores, never()).save(any());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void archivaSectorConPacientesAsignadosSinConsultasActivas() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                Paciente paciente = new Paciente();
                sector.setPacientesAsignados(java.util.List.of(paciente));
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                assertTrue(sector.isArchivado());
                assertFalse(sector.isActiva());
                assertEquals(List.of(paciente), sector.getPacientesAsignados());
                verify(sectores).save(sector);
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void eliminaSectorDesvinculaSalasVacias() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                Sala sala = new Sala();
                sala.setId(100L);
                sala.setNombre("Sala 1");
                sala.setSector(sector);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of(sala));
                when(consultasMedicas.existsBySalaIdInAndEstadoConsultaNotIn(anyCollection(), anyCollection()))
                                .thenReturn(false);
                when(sesionesAtencionMedica.existsBySalaIdInAndEstadoIn(anyCollection(), anyCollection()))
                                .thenReturn(false);

                service.eliminarSector(actor.getId(), hospital.getId(), 22L);

                verify(salas).deleteAllByIdInBatch(List.of(sala.getId()));
                verify(sectores).deleteAllByIdInBatch(List.of(sector.getId()));
        }

        @Test
        void noEliminaSectorSiTienePacientes() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                Sala sala = new Sala();
                sala.setId(100L);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of(sala));
                when(consultasMedicas.existsBySalaIdInAndEstadoConsultaNotIn(anyCollection(), anyCollection()))
                                .thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 22L));

                assertEquals("No se puede modificar o eliminar un sector con pacientes pendientes o en atención",
                                error.getMessage());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void noEliminaSectorSiTieneSesionActivaOPausada() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                Sala sala = new Sala();
                sala.setId(100L);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of(sala));
                when(consultasMedicas.existsBySalaIdInAndEstadoConsultaNotIn(anyCollection(), anyCollection()))
                                .thenReturn(false);
                when(sesionesAtencionMedica.existsBySalaIdInAndEstadoIn(anyCollection(), anyCollection()))
                                .thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 22L));

                assertEquals("No se puede modificar o eliminar un sector con sesiones de atención activas o pausadas",
                                error.getMessage());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void noEliminaSectorConSesionPausadaAsignadaAlSectorSinSala() {
                Sector sector = new Sector();
                sector.setId(22L);
                sector.setNombre("Sector Norte");
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                when(sectores.findByIdAndHospitalIdForUpdate(22L, hospital.getId())).thenReturn(Optional.of(sector));
                when(salas.findBySectorId(22L)).thenReturn(java.util.List.of());
                when(sesionesAtencionMedica.existsBySectorIdAndEstadoIn(eq(22L), anyCollection())).thenReturn(true);

                ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 22L));

                assertEquals("No se puede modificar o eliminar un sector con sesiones de atención activas o pausadas",
                                error.getMessage());
                verify(sectores, never()).deleteAllByIdInBatch(any());
        }

        @Test
        void noEliminaSectorSiNoExiste() {
                when(sectores.findByIdAndHospitalIdForUpdate(99L, hospital.getId())).thenReturn(Optional.empty());

                assertThrows(RecursoNoEncontradoException.class,
                                () -> service.eliminarSector(actor.getId(), hospital.getId(), 99L));
        }

        @Test
        void noActualizaSectorSiNoExiste() {
                when(sectores.findByIdAndHospitalId(99L, hospital.getId())).thenReturn(Optional.empty());

                assertThrows(RecursoNoEncontradoException.class,
                                () -> service.actualizarSector(actor.getId(), hospital.getId(), 99L,
                                                new ActualizarSectorRequest("X", especialidad.getId(), true)));
        }
}
