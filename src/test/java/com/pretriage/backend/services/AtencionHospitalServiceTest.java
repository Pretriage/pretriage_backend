package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EsperaNuevaConsultaCalculo;
import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;
import com.pretriage.backend.controllers.dtos.HospitalCercanoDTO;
import com.pretriage.backend.controllers.dtos.HospitalSeleccionadoResponse;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.AtencionEnCursoException;
import com.pretriage.backend.exceptions.NoSePudoEstimarElHorarioDeAtencion;
import com.pretriage.backend.exceptions.PacienteSancionadoException;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.repositories.RepoGestoresDeColas;
import com.pretriage.backend.repositories.RepoHospitales;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AtencionHospitalServiceTest {

        @Mock
        private RepoConsultasMedicas repoConsultasMedicas;
        @Mock
        private RepoHospitales repoHospitales;
        @Mock
        private RepoGestoresDeColas repoGestorDeCola;
        @Mock
        private RepoEntradasCola repoEntradasCola;
        @Mock
        private RepoEspecialidadesMedicas repoEspecialidadesMedicas;
        @Mock
        private EstimacionAtencionService estimacionAtencionService;
        @Mock
        private PacienteService pacienteService;
        @Mock
        private IngresoColaService ingresoColaService;
        @Mock
        private GooglePlacesService googlePlacesService;
        @Mock
        private AsignacionSectorService asignacionSectorService;
        @Mock
        private DiscrepanciaSintomasService discrepanciaSintomasService;

        @InjectMocks
        private AtencionHospitalService service;

        @BeforeEach
        void pacienteSinSancionPorDefecto() {
                lenient().when(discrepanciaSintomasService.estadoParaIngresoDigital(any(Paciente.class)))
                                .thenReturn(new EstadoSancionPacienteDTO(false, false, null, null, null));
        }

        @Test
        void rechazaUnaNuevaSolicitudDigitalDuranteLaRestriccion() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                var sancion = new EstadoSancionPacienteDTO(true, true, null, "sancion-1", null);
                when(discrepanciaSintomasService.estadoParaIngresoDigital(paciente)).thenReturn(sancion);

                var error = assertThrows(PacienteSancionadoException.class,
                                () -> service.seleccionarHospital(auth0Id, "place_1", "PEDIATRIA"));
                assertEquals(sancion, error.getSancion());
                verifyNoInteractions(ingresoColaService);
        }

        @Test
        void pretriageLegadoNoAbreUnaEntradaNuevaSiElPacienteEstaSancionado() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                ConsultaMedica consulta = crearConsultaPendiente(paciente);
                consulta.setId(20L);
                consulta.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));
                when(discrepanciaSintomasService.estadoParaIngresoDigital(paciente))
                                .thenReturn(new EstadoSancionPacienteDTO(true, true, null, "sancion-1", null));

                assertThrows(PacienteSancionadoException.class,
                                () -> service.finalizarTriageEIngresarACola(auth0Id, NivelDeGravedad.URGENTE, "{}"));
                assertNull(consulta.getResumenPretriageJson());
                verifyNoInteractions(ingresoColaService);
        }

        @Test
        void pretriageEnUnaEntradaExistenteConservaSuPrioridadClinicaSinConsultarSanciones() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                ConsultaMedica consulta = crearConsultaPendiente(paciente);
                consulta.setId(20L);
                consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));
                when(repoEntradasCola.existsByConsultaMedicaId(20L)).thenReturn(true);

                service.finalizarTriageEIngresarACola(auth0Id, NivelDeGravedad.RIESGO_VITAL_INMEDIATO, "{}");

                verify(ingresoColaService).ingresar(consulta, NivelDeGravedad.RIESGO_VITAL_INMEDIATO);
                verifyNoInteractions(discrepanciaSintomasService);
        }

        @Test
        void sePuedeSeleccionarUnHospitalConEspecialidad() {
                String auth0Id = "auth0|123";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital = crearHospital(20L, placeId, especialidad);
                Sector sector = crearSector(50L, hospital, especialidad);
                ConsultaMedica consultaMedica = crearConsultaPendiente(paciente);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaMedica));
                when(repoHospitales.findByPlaceId(placeId)).thenReturn(Optional.of(hospital));
                when(asignacionSectorService.asignarSector(consultaMedica)).thenAnswer(inv -> {
                        consultaMedica.setSector(sector);
                        return null;
                });
                when(ingresoColaService.ingresar(consultaMedica, NivelDeGravedad.NORMAL, sector)).thenAnswer(inv -> {
                        consultaMedica.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
                        consultaMedica.setEstadoConsulta(EstadoConsulta.EN_COLA);
                        return new TiempoEstimadoAtencionResponse();
                });

                service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad);

                assertSame(hospital, consultaMedica.getHospital());
                assertSame(especialidad, consultaMedica.getEspecialidad());
                assertSame(sector, consultaMedica.getSector());
                assertEquals(EstadoConsulta.EN_COLA, consultaMedica.getEstadoConsulta());
                verify(asignacionSectorService).asignarSector(consultaMedica);
                verify(ingresoColaService).ingresar(consultaMedica, NivelDeGravedad.NORMAL, sector);
        }

        @Test
        void noSePuedeSeleccionarHospitalSiNoHaySectoresDisponibles() {
                String auth0Id = "auth0|123";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital = crearHospital(20L, placeId, especialidad);
                ConsultaMedica consultaMedica = crearConsultaPendiente(paciente);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaMedica));
                when(repoHospitales.findByPlaceId(placeId)).thenReturn(Optional.of(hospital));
                when(asignacionSectorService.asignarSector(consultaMedica))
                                .thenThrow(new NoSuchElementException("No hay sectores disponibles"));

                assertThrows(NoSuchElementException.class,
                                () -> service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad));

                verifyNoInteractions(ingresoColaService);
        }

        @Test
        void noSePuedeSeleccionarHospitalSiNoAtiendeLaEspecialidad() {
                String auth0Id = "auth0|123";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital = crearHospital(20L, placeId, crearEspecialidad(31L, "CARDIOLOGIA"));
                ConsultaMedica consultaMedica = crearConsultaPendiente(paciente);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaMedica));
                when(repoHospitales.findByPlaceId(placeId)).thenReturn(Optional.of(hospital));

                assertThrows(NoSuchElementException.class,
                                () -> service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad));

                verify(repoConsultasMedicas, never()).save(any());
        }

        @Test
        void seleccionarHospitalLuegoFinalizarTriageAgregaPacienteALaColaDeLaEspecialidadYDevuelveTiempoEstimado() {
                String auth0Id = "auth0|123";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital = crearHospital(20L, placeId, especialidad);
                Sector sector = crearSector(50L, hospital, especialidad);
                ConsultaMedica consultaPaciente = crearConsultaPendiente(paciente);
                consultaPaciente.setFechaHoraCreacion(LocalDateTime.of(2026, 6, 29, 10, 5));

                ConsultaMedica consultaCriticaPrevia = new ConsultaMedica();
                consultaCriticaPrevia.setHospital(hospital);
                consultaCriticaPrevia.setEspecialidad(especialidad);
                consultaCriticaPrevia.setEstadoConsulta(EstadoConsulta.PENDIENTE);
                consultaCriticaPrevia.setFechaHoraCreacion(LocalDateTime.of(2026, 6, 29, 10, 0));
                consultaCriticaPrevia.setNivelDeGravedadBot(NivelDeGravedad.RIESGO_VITAL_INMEDIATO);

                GestorDeCola gestorDeCola = new GestorDeCola();
                gestorDeCola.setId(40L);
                gestorDeCola.setHospital(hospital);
                gestorDeCola.setEspecialidad(especialidad);
                gestorDeCola.agregarConsultaMedicaALaCola(consultaCriticaPrevia);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaPaciente));
                when(repoHospitales.findByPlaceId(placeId)).thenReturn(Optional.of(hospital));
                when(asignacionSectorService.asignarSector(consultaPaciente)).thenAnswer(inv -> {
                        consultaPaciente.setSector(sector);
                        return null;
                });

                TiempoEstimadoAtencionResponse responseEsperada = new TiempoEstimadoAtencionResponse();
                responseEsperada.setPosicionEnCola(2);
                responseEsperada.setPacientesAntes(1);
                when(ingresoColaService.ingresar(consultaPaciente, NivelDeGravedad.NORMAL, sector)).thenAnswer(inv -> {
                        consultaPaciente.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
                        consultaPaciente.setEstadoConsulta(EstadoConsulta.EN_COLA);
                        return new TiempoEstimadoAtencionResponse();
                });
                when(ingresoColaService.ingresar(consultaPaciente, NivelDeGravedad.URGENTE)).thenAnswer(inv -> {
                        consultaPaciente.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
                        consultaPaciente.setEstadoConsulta(EstadoConsulta.EN_COLA);
                        return responseEsperada;
                });

                service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad);

                TiempoEstimadoAtencionResponse response = service.finalizarTriageEIngresarACola(auth0Id,
                                NivelDeGravedad.URGENTE);

                assertSame(hospital, consultaPaciente.getHospital());
                assertSame(especialidad, consultaPaciente.getEspecialidad());
                assertSame(sector, consultaPaciente.getSector());
                assertEquals(EstadoConsulta.EN_COLA, consultaPaciente.getEstadoConsulta());
                assertEquals(NivelDeGravedad.URGENTE, consultaPaciente.getNivelDeGravedadBot());
                assertSame(responseEsperada, response);
                assertNull(consultaPaciente.getChat());
                verify(asignacionSectorService).asignarSector(consultaPaciente);
                verify(ingresoColaService).ingresar(consultaPaciente, NivelDeGravedad.NORMAL, sector);
                verify(ingresoColaService).ingresar(consultaPaciente, NivelDeGravedad.URGENTE);
        }

        @Test
        void finalizarTriageConChatVinculaLaConsultaAlChatDelPretriage() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                ConsultaMedica consultaPaciente = crearConsultaPendiente(paciente);
                consultaPaciente.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);
                Chat chat = new Chat(paciente);
                chat.setConsultaOrigenId(20L);
                TiempoEstimadoAtencionResponse responseEsperada = new TiempoEstimadoAtencionResponse();

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaPaciente));
                when(ingresoColaService.ingresar(consultaPaciente, NivelDeGravedad.URGENTE)).thenReturn(responseEsperada);

                TiempoEstimadoAtencionResponse response = service.finalizarTriageEIngresarACola(auth0Id,
                                NivelDeGravedad.URGENTE, "{\"nivelPrioridad\":3}", chat);

                assertSame(chat, consultaPaciente.getChat());
                assertEquals("{\"nivelPrioridad\":3}", consultaPaciente.getResumenPretriageJson());
                assertSame(responseEsperada, response);
                verify(repoConsultasMedicas).save(consultaPaciente);
        }

        @Test
        void chatLegadoCompletaElResumenSinReencolarUnaConsultaEnRevision() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                ConsultaMedica consulta = crearConsultaPendiente(paciente);
                consulta.setId(20L);
                consulta.setEstadoConsulta(EstadoConsulta.EN_REVISION);
                Chat chat = new Chat(paciente);
                EntityManager entityManager = mock(EntityManager.class);
                ReflectionTestUtils.setField(service, "entityManager", entityManager);
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));
                when(repoConsultasMedicas.findByIdForUpdate(20L)).thenReturn(Optional.of(consulta));

                assertNull(service.finalizarTriageEIngresarACola(auth0Id, NivelDeGravedad.URGENTE,
                                "{\"nivelPrioridad\":3}", chat));

                assertEquals(EstadoConsulta.EN_REVISION, consulta.getEstadoConsulta());
                assertEquals(NivelDeGravedad.URGENTE, consulta.getNivelDeGravedadBot());
                assertSame(chat, consulta.getChat());
                verify(entityManager).refresh(consulta);
                verifyNoInteractions(ingresoColaService);
        }

        @Test
        void finalizarTriageSinChatDejaLaConsultaSinVinculo() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(10L);
                ConsultaMedica consultaPaciente = crearConsultaPendiente(paciente);
                consultaPaciente.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaPaciente));
                when(ingresoColaService.ingresar(consultaPaciente, NivelDeGravedad.NORMAL))
                                .thenReturn(new TiempoEstimadoAtencionResponse());

                service.finalizarTriageEIngresarACola(auth0Id, NivelDeGravedad.NORMAL, null);

                assertNull(consultaPaciente.getChat());
        }

        @Test
        void hospitalesCercanosSeFiltranPorEspecialidadManteniendoElOrdenDeDistancia() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalDisponible = crearHospital(20L, "hospital2", especialidad);

                HospitalCercanoDTO hospital1 = crearHospitalCercano("hospital1");
                HospitalCercanoDTO hospital2 = crearHospitalCercano("hospital2");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(hospital1, hospital2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalDisponible));

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(anyLong(), anyLong()))
                                .thenReturn(new EsperaNuevaConsultaCalculo(0, 0, LocalDateTime.now(), true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente);

                assertEquals(1, hospitales.size());
                assertEquals("hospital2", hospitales.getFirst().getPlaceId());
                assertEquals(codigoEspecialidad, hospitales.getFirst().getEspecialidades().getFirst().getCodigo());
        }

        @Test
        void seCompletaElTiempoEstimadoArriboMejorRutaEnHospitalesCercanos() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                String transporte = "vehiculo";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalDisponible = crearHospital(20L, "hospital2", especialidad);

                HospitalCercanoDTO hospital2 = crearHospitalCercano("hospital2");

                LocalTime tiempoEsperado = LocalTime.of(0, 25, 0);

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(hospital2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalDisponible));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(hospitalDisponible, transporte, -34.6,
                                -58.4))
                                .thenReturn(tiempoEsperado);
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(anyLong(), anyLong()))
                                .thenReturn(new EsperaNuevaConsultaCalculo(2, 20, LocalDateTime.now().plusMinutes(20),
                                                true));

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                transporte, auth0idPaciente);

                assertEquals(1, hospitales.size());
                assertEquals(tiempoEsperado, hospitales.getFirst().getTiempoEstimadoArriboMejorRuta());
                assertEquals(2, hospitales.getFirst().getPacientesEnCola());
                assertEquals(20L, hospitales.getFirst().getMinutosEsperaEstimados());
                assertTrue(hospitales.getFirst().isDisponible());
        }

        @Test
        void siNoSeEspecificaTransporteSeUsaElPredeterminado() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalDisponible = crearHospital(20L, "hospital2", especialidad);

                HospitalCercanoDTO hospital2 = crearHospitalCercano("hospital2");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(hospital2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalDisponible));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(hospitalDisponible, "transporte-publico",
                                -34.6,
                                -58.4)).thenReturn(LocalTime.of(0, 15, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(anyLong(), anyLong()))
                                .thenReturn(new EsperaNuevaConsultaCalculo(0, 0, LocalDateTime.now(), true));

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                null, auth0idPaciente);

                assertEquals(1, hospitales.size());
                assertEquals(LocalTime.of(0, 15, 0), hospitales.getFirst().getTiempoEstimadoArriboMejorRuta());
        }

        @Test
        void siNoSePuedeCalcularElTiempoDeArriboElCampoQuedaNull() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalDisponible = crearHospital(20L, "hospital2", especialidad);

                HospitalCercanoDTO hospital2 = crearHospitalCercano("hospital2");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(hospital2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalDisponible));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(hospitalDisponible, "transporte-publico",
                                -34.6,
                                -58.4)).thenReturn(null);
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(anyLong(), anyLong()))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "transporte-publico", auth0idPaciente);

                assertEquals(1, hospitales.size());
                assertNull(hospitales.getFirst().getTiempoEstimadoArriboMejorRuta());
        }

        @Test
        void soloSeCalculaLaRutaParaLosHospitalesDisponiblesMasCercanos() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                List<Hospital> registrados = new java.util.ArrayList<>();
                List<HospitalCercanoDTO> cercanos = new java.util.ArrayList<>();
                for (int i = 1; i <= 7; i++) {
                        registrados.add(crearHospital((long) i, "hospital" + i, especialidad));
                        cercanos.add(crearHospitalCercano("hospital" + i));
                }

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad)).thenReturn(registrados);
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(cercanos);
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(anyLong(), anyLong()))
                                .thenReturn(new EsperaNuevaConsultaCalculo(0, 0, LocalDateTime.now(), true));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), eq("transporte-publico"),
                                eq(-34.6), eq(-58.4))).thenReturn(LocalTime.of(0, 10, 0));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                null, auth0idPaciente);

                assertEquals(7, hospitales.size());
                verify(googlePlacesService, times(AtencionHospitalService.MAX_HOSPITALES_CON_RUTA))
                                .calcularTiempoEstimadoArriboMejorRuta(any(), any(), any(), any());
                assertEquals(LocalTime.of(0, 10, 0), hospitales.get(4).getTiempoEstimadoArriboMejorRuta());
                assertNull(hospitales.get(5).getTiempoEstimadoArriboMejorRuta());
                assertNull(hospitales.get(6).getTiempoEstimadoArriboMejorRuta());
        }

        @Test
        void noSePuedeSeleccionarUnHospitalQueNoExiste() {
                String auth0Id = "auth0|123";
                String placeId = "place_inexistente";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                ConsultaMedica consultaMedica = crearConsultaPendiente(paciente);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaMedica));
                when(repoHospitales.findByPlaceId(placeId)).thenReturn(Optional.empty());
                when(googlePlacesService.obtenerHospitalDesdeGoogle(placeId))
                                .thenThrow(new NoSuchElementException("Hospital inexistente"));

                assertThrows(NoSuchElementException.class,
                                () -> service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad));

                verify(repoHospitales, never()).save(any());
                verifyNoInteractions(repoGestorDeCola, ingresoColaService);
                verify(repoConsultasMedicas, never()).save(any());
        }

        @Test
        void noSePuedeSeleccionarHospitalSiYaTieneUnaAtencionEnCurso() {
                String auth0Id = "auth0|123";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                Paciente paciente = crearPaciente(10L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                ConsultaMedica consultaEnCurso = new ConsultaMedica();
                consultaEnCurso.setPaciente(paciente);
                consultaEnCurso.setEstadoConsulta(EstadoConsulta.HOSPITAL_SELECCIONADO);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consultaEnCurso));

                assertThrows(AtencionEnCursoException.class,
                                () -> service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad));

                verifyNoInteractions(repoHospitales, googlePlacesService, repoGestorDeCola);
                verify(repoConsultasMedicas, never()).save(any());
        }

        @Test
        void noSePuedeSeleccionarUnHospitalSinotienePermisos() {
                String auth0Id = "auth0|sin-permiso";
                String placeId = "place_1";
                String codigoEspecialidad = "PEDIATRIA";

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.empty());

                assertThrows(AccessDeniedException.class,
                                () -> service.seleccionarHospital(auth0Id, placeId, codigoEspecialidad));

                verifyNoInteractions(repoConsultasMedicas, repoHospitales, googlePlacesService,
                                repoEspecialidadesMedicas);
        }

        @Test
        void sePuedeObtenerTiempoEstimadoDeAtencion() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, "PEDIATRIA");
                Hospital hospital = crearHospital(10L, "place_1", especialidad);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setHospital(hospital);
                consulta.setEspecialidad(especialidad);

                TiempoEstimadoAtencionResponse responseEsperada = new TiempoEstimadoAtencionResponse();
                responseEsperada.setFechaHoraAtencionEstimada(LocalDateTime.of(2026, 6, 20, 15, 30));

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));
                when(estimacionAtencionService.calcularPara(consulta)).thenReturn(responseEsperada);

                TiempoEstimadoAtencionResponse response = service.obtenerTiempoEstimadoDeAtencion(auth0Id);

                assertSame(responseEsperada, response);
        }

        @Test
        void noSePuedeObtenerTiempoEstimadoSinPermisos() {
                String auth0Id = "auth0|123";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.empty());

                assertThrows(AccessDeniedException.class, () -> service.obtenerTiempoEstimadoDeAtencion(auth0Id));

                verifyNoInteractions(repoConsultasMedicas);
        }

        @Test
        void noSePuedeObtenerTiempoEstimadoSinHospitalSeleccionado() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.empty());

                assertThrows(NoSuchElementException.class, () -> service.obtenerTiempoEstimadoDeAtencion(auth0Id));
        }

        @Test
        void sePuedeObtenerHospitalSeleccionadoConDireccionFormateada() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, "PEDIATRIA");
                Hospital hospital = crearHospital(10L, "place_1", especialidad);
                hospital.setNombre("Hospital Central");

                Direccion direccion = new Direccion();
                direccion.setCalle("Av. Siempre Viva");
                direccion.setAltura("742");
                direccion.setCiudad("CABA");
                direccion.setProvincia("Buenos Aires");
                hospital.setDireccion(direccion);

                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setHospital(hospital);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));

                HospitalSeleccionadoResponse response = service.obtenerHospitalSeleccionado(auth0Id);

                assertEquals(10L, response.getIdHospital());
                assertEquals("place_1", response.getPlaceId());
                assertEquals("Hospital Central", response.getNombre());
                assertEquals("Av. Siempre Viva 742, CABA, Buenos Aires", response.getDireccion());
        }

        @Test
        void seDevuelveDireccionNullSiElHospitalSeleccionadoNoTieneDireccion() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, "PEDIATRIA");
                Hospital hospital = crearHospital(10L, "place_1", especialidad);
                hospital.setNombre("Hospital Central");

                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setHospital(hospital);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));

                HospitalSeleccionadoResponse response = service.obtenerHospitalSeleccionado(auth0Id);

                assertEquals("Hospital Central", response.getNombre());
                assertNull(response.getDireccion());
        }

        @Test
        void noSePuedeObtenerHospitalSeleccionadoSinHospitalElegido() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.empty());

                assertThrows(NoSuchElementException.class, () -> service.obtenerHospitalSeleccionado(auth0Id));
        }

        @Test
        void lanzaExcepcionSiNoSePuedeEstimarElHorario() {
                String auth0Id = "auth0|123";
                Paciente paciente = crearPaciente(1L);
                EspecialidadMedica especialidad = crearEspecialidad(30L, "PEDIATRIA");
                Hospital hospital = crearHospital(10L, "place_1", especialidad);
                ConsultaMedica consulta = new ConsultaMedica();
                consulta.setHospital(hospital);
                consulta.setEspecialidad(especialidad);

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id)).thenReturn(Optional.of(paciente));
                when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(paciente.getId()), any()))
                                .thenReturn(Optional.of(consulta));
                when(estimacionAtencionService.calcularPara(consulta))
                                .thenThrow(new NoSePudoEstimarElHorarioDeAtencion());

                assertThrows(NoSePudoEstimarElHorarioDeAtencion.class,
                                () -> service.obtenerTiempoEstimadoDeAtencion(auth0Id));
        }

        @Test
        void ordenaHospitalesPorMenorTiempoDeAtencionCuandoSePide() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital1 = crearHospital(20L, "hospital1", especialidad);
                Hospital hospital2 = crearHospital(21L, "hospital2", especialidad);

                HospitalCercanoDTO dto1 = crearHospitalCercano("hospital1");
                HospitalCercanoDTO dto2 = crearHospitalCercano("hospital2");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dto1, dto2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospital1, hospital2));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(5, 50, LocalDateTime.now().plusMinutes(50),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "tiempo-atencion");

                assertEquals(2, hospitales.size());
                assertEquals("hospital2", hospitales.get(0).getPlaceId());
                assertEquals("hospital1", hospitales.get(1).getPlaceId());
        }

        @Test
        void excluyeHospitalesSinMedicosActivos() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital1 = crearHospital(20L, "hospital1", especialidad);
                Hospital hospital2 = crearHospital(21L, "hospital2", especialidad);

                HospitalCercanoDTO dto1 = crearHospitalCercano("hospital1");
                HospitalCercanoDTO dto2 = crearHospitalCercano("hospital2");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dto1, dto2));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospital1, hospital2));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(3, 30, LocalDateTime.now().plusMinutes(30),
                                                false));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(2, 20, LocalDateTime.now().plusMinutes(20),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente);

                assertEquals(1, hospitales.size());
                assertEquals("hospital2", hospitales.getFirst().getPlaceId());
        }

        @Test
        void devuelveListaVaciaSiNingunHospitalEstaDisponible() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital1 = crearHospital(20L, "hospital1", especialidad);

                HospitalCercanoDTO dto1 = crearHospitalCercano("hospital1");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dto1));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospital1));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(5, 50, LocalDateTime.now().plusMinutes(50),
                                                false));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente);

                assertTrue(hospitales.isEmpty());
                // sin hospitales disponibles no se gasta ninguna consulta de rutas en Google
                verify(googlePlacesService, never()).calcularTiempoEstimadoArriboMejorRuta(any(), any(), any(), any());
        }

        @Test
        void rechazaOrdenarPorInvalido() {
                String auth0idPaciente = "auth0|Paciente";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "invalido"));
        }

        @Test
        void completaLosCamposDeTiempoEstimadoEnCadaHospital() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospital1 = crearHospital(20L, "hospital1", especialidad);

                HospitalCercanoDTO dto1 = crearHospitalCercano("hospital1");
                LocalDateTime fechaEsperada = LocalDateTime.of(2026, 8, 24, 12, 0);

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dto1));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospital1));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(3, 30, fechaEsperada, true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente);

                assertEquals(1, hospitales.size());
                HospitalCercanoDTO result = hospitales.getFirst();
                assertEquals(3, result.getPacientesEnCola());
                assertEquals(30L, result.getMinutosEsperaEstimados());
                assertEquals(fechaEsperada, result.getFechaHoraAtencionEstimada());
                assertTrue(result.isDisponible());
        }

        @Test
        void ordenaConPuntuacionCombinadaSumandoRankings() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);
                Hospital hospitalC = crearHospital(22L, "hospitalC", especialidad);

                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA");
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB");
                HospitalCercanoDTO dtoC = crearHospitalCercano("hospitalC");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB, dtoC));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB, hospitalC));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(5, 50, LocalDateTime.now().plusMinutes(50),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(22L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(2, 30, LocalDateTime.now().plusMinutes(30),
                                                true));

                List<HospitalCercanoDTO> resultado1 = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "distancia|tiempo-atencion");
                List<HospitalCercanoDTO> resultado2 = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "tiempo-atencion|distancia");

                assertEquals(List.of("hospitalB", "hospitalA", "hospitalC"),
                                resultado1.stream().map(HospitalCercanoDTO::getPlaceId).toList());
                assertEquals(List.of("hospitalB", "hospitalA", "hospitalC"),
                                resultado2.stream().map(HospitalCercanoDTO::getPlaceId).toList());
        }

        @Test
        void rechazaCombinacionesInvalidasDeOrdenarPor() {
                String auth0idPaciente = "auth0|Paciente";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "distancia&distancia"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "distancia&invalido"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "&distancia"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, ""));
        }

        @Test
        void ordernaPorValoracionDeMayorAMenorConSinValoracionAlFinal() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);
                Hospital hospitalC = crearHospital(22L, "hospitalC", especialidad);

                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA");
                dtoA.setValoracionPromedio(4.0);
                dtoA.setCantidadValoraciones(50);
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB"); // sin valoracion
                HospitalCercanoDTO dtoC = crearHospitalCercano("hospitalC");
                dtoC.setValoracionPromedio(4.9);
                dtoC.setCantidadValoraciones(10);

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB, dtoC));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB, hospitalC));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(22L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "valoracion");

                assertEquals(List.of("hospitalC", "hospitalA", "hospitalB"),
                                hospitales.stream().map(HospitalCercanoDTO::getPlaceId).toList());
        }

        @Test
        void desempataLaValoracionPorCantidadDeValoraciones() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);

                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA");
                dtoA.setValoracionPromedio(4.0);
                dtoA.setCantidadValoraciones(5);
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB");
                dtoB.setValoracionPromedio(4.0);
                dtoB.setCantidadValoraciones(100);

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "valoracion");

                assertEquals(List.of("hospitalB", "hospitalA"),
                                hospitales.stream().map(HospitalCercanoDTO::getPlaceId).toList());
        }

        @Test
        void ordenPorValoracionNoVaciaCuandoNingunHospitalTieneValoracion() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);

                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA");
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB");

                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "valoracion");

                assertEquals(2, hospitales.size());
                assertTrue(hospitales.stream().map(HospitalCercanoDTO::getPlaceId)
                                .toList()
                                .containsAll(List.of("hospitalA", "hospitalB")));
        }

        @Test
        void rechazaValoracionConMayusculas() {
                String auth0idPaciente = "auth0|Paciente";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "Valoracion"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "vALORaCiOn"));
        }

        @Test
        void rechazaValoracionInvalida() {
                String auth0idPaciente = "auth0|Paciente";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "valoracion-invalida"));
        }

        @Test
        void rechazaCombinacionConCriterioInvalidoAunqueOtroSeaValido() {
                String auth0idPaciente = "auth0|Paciente";
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));

                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "valoracion|foo"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "foo|valoracion"));
                assertThrows(IllegalArgumentException.class,
                                () -> service.buscarHospitalesCercanos(-34.6, -58.4, "PEDIATRIA",
                                                "caminar", auth0idPaciente, "valoracion|valoracion"));
        }

        @Test
        void ordernaCombinadoValoracionConDistanciaPorSumaDeRankings() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);
                Hospital hospitalC = crearHospital(22L, "hospitalC", especialidad);

                // orden Google (distancia): A, B, C
                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA"); // sin valoracion -> rank 2
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB"); // 4.5 -> rank 0
                dtoB.setValoracionPromedio(4.5);
                dtoB.setCantidadValoraciones(80);
                HospitalCercanoDTO dtoC = crearHospitalCercano("hospitalC"); // 4.0 -> rank 1
                dtoC.setValoracionPromedio(4.0);
                dtoC.setCantidadValoraciones(20);

                // suma: A=0+2=2, B=1+0=1, C=2+1=3 -> B, A, C
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB, dtoC));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB, hospitalC));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(22L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));

                List<HospitalCercanoDTO> hospitales = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "valoracion|distancia");

                assertEquals(List.of("hospitalB", "hospitalA", "hospitalC"),
                                hospitales.stream().map(HospitalCercanoDTO::getPlaceId).toList());
        }

        @Test
        void ordernaCombinadoDeLosTresCriteriosPorSumaDeRankings() {
                String auth0idPaciente = "auth0|Paciente";
                String codigoEspecialidad = "PEDIATRIA";
                EspecialidadMedica especialidad = crearEspecialidad(30L, codigoEspecialidad);
                Hospital hospitalA = crearHospital(20L, "hospitalA", especialidad);
                Hospital hospitalB = crearHospital(21L, "hospitalB", especialidad);
                Hospital hospitalC = crearHospital(22L, "hospitalC", especialidad);

                // orden Google (distancia): A, B, C -> ranks 0, 1, 2
                HospitalCercanoDTO dtoA = crearHospitalCercano("hospitalA"); // sin valoracion -> rank 2; espera 50 -> rank 2
                HospitalCercanoDTO dtoB = crearHospitalCercano("hospitalB"); // 4.5 -> rank 0; espera 10 -> rank 0
                dtoB.setValoracionPromedio(4.5);
                dtoB.setCantidadValoraciones(80);
                HospitalCercanoDTO dtoC = crearHospitalCercano("hospitalC"); // 4.0 -> rank 1; espera 30 -> rank 1
                dtoC.setValoracionPromedio(4.0);
                dtoC.setCantidadValoraciones(20);

                // suma: A=0+2+2=4, B=1+0+0=1, C=2+1+1=4 -> empate A/C -> nombre ASC -> B, A, C
                when(repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)).thenReturn(Optional.of(especialidad));
                when(googlePlacesService.buscarHospitalesRegistrados(eq(-34.6), eq(-58.4), anyList())).thenReturn(List.of(dtoA, dtoB, dtoC));
                when(repoHospitales.findDistinctByEspecialidadesCodigo(codigoEspecialidad))
                                .thenReturn(List.of(hospitalA, hospitalB, hospitalC));
                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0idPaciente))
                                .thenReturn(Optional.of(new Paciente()));
                when(googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(any(), anyString(), anyDouble(),
                                anyDouble()))
                                .thenReturn(LocalTime.of(0, 10, 0));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(20L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(5, 50, LocalDateTime.now().plusMinutes(50),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(21L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(1, 10, LocalDateTime.now().plusMinutes(10),
                                                true));
                when(estimacionAtencionService.calcularEsperaParaNuevaConsulta(22L, 30L))
                                .thenReturn(new EsperaNuevaConsultaCalculo(2, 30, LocalDateTime.now().plusMinutes(30),
                                                true));

                List<HospitalCercanoDTO> resultado = service.buscarHospitalesCercanos(-34.6, -58.4, codigoEspecialidad,
                                "caminar", auth0idPaciente, "distancia|tiempo-atencion|valoracion");

                assertEquals(List.of("hospitalB", "hospitalA", "hospitalC"),
                                resultado.stream().map(HospitalCercanoDTO::getPlaceId).toList());
        }

        private Paciente crearPaciente(Long id) {
                Paciente paciente = new Paciente();
                paciente.setId(id);
                return paciente;
        }

        private ConsultaMedica crearConsultaPendiente(Paciente paciente) {
                ConsultaMedica consultaMedica = new ConsultaMedica();
                consultaMedica.setPaciente(paciente);
                consultaMedica.setEstadoConsulta(EstadoConsulta.PENDIENTE);
                consultaMedica.setFechaHoraCreacion(LocalDateTime.now().minusHours(1));
                return consultaMedica;
        }

        private Hospital crearHospital(Long id, String placeId, EspecialidadMedica especialidad) {
                Hospital hospital = new Hospital();
                hospital.setId(id);
                hospital.setPlaceId(placeId);
                hospital.getEspecialidades().add(especialidad);
                return hospital;
        }

        private EspecialidadMedica crearEspecialidad(Long id, String codigo) {
                EspecialidadMedica especialidad = new EspecialidadMedica();
                especialidad.setId(id);
                especialidad.setCodigo(codigo);
                especialidad.setNombre(codigo);
                return especialidad;
        }

        private Sector crearSector(Long id, Hospital hospital, EspecialidadMedica especialidad) {
                Sector sector = new Sector();
                sector.setId(id);
                sector.setNombre("Sector " + id);
                sector.setHospital(hospital);
                sector.setEspecialidad(especialidad);
                return sector;
        }

        private HospitalCercanoDTO crearHospitalCercano(String placeId) {
                HospitalCercanoDTO hospital = new HospitalCercanoDTO();
                hospital.setPlaceId(placeId);
                hospital.setNombre(placeId);
                hospital.setDireccion("direccion " + placeId);
                return hospital;
        }
}
