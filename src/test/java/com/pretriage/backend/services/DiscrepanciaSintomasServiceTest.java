package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.DiscrepanciaSintomasDTO;
import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.DiscrepanciaSintomas;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoDiscrepanciaSintomas;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.consultas.SancionDigitalPaciente;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.consultas.TipoAvisoSancion;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAtencionesMedicas;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoDiscrepanciasSintomas;
import com.pretriage.backend.repositories.RepoSancionesDigitales;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscrepanciaSintomasServiceTest {
    private static final String MEDICO = "medico-1";
    private static final String ADMIN = "admin-1";
    private static final String DNI = "12345678";
    private static final Long SESION = 1L;
    private static final Long CONSULTA = 2L;
    private static final Long ATENCION = 3L;
    private static final Long HOSPITAL = 4L;
    private static final Long DISCREPANCIA = 5L;

    @Mock RepoDiscrepanciasSintomas repoDiscrepancias;
    @Mock RepoSancionesDigitales repoSanciones;
    @Mock RepoAtencionesMedicas repoAtenciones;
    @Mock RepoSesionesAtencionMedica repoSesiones;
    @Mock RepoAuditoriasHospital repoAuditorias;
    @Mock StaffAccessService staffAccessService;
    @Mock JdbcTemplate jdbcTemplate;

    private DiscrepanciaSintomasService service;
    private Paciente paciente;
    private ConsultaMedica consulta;
    private SesionAtencionMedica sesion;
    private AtencionMedica atencion;

    @BeforeEach
    void setUp() {
        service = new DiscrepanciaSintomasService(repoDiscrepancias, repoSanciones, repoAtenciones, repoSesiones,
                repoAuditorias, staffAccessService, jdbcTemplate);
        paciente = new Paciente();
        paciente.setTipoDocumento(TipoDocumento.DNI);
        paciente.setNumeroDocumento(DNI);
        consulta = new ConsultaMedica();
        consulta.setId(CONSULTA);
        consulta.setPaciente(paciente);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ATENCION);
        consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
        consulta.setNivelDeGravedadMedico(NivelDeGravedad.NORMAL);
        consulta.setResumenPretriageJson("{\"sintomas\":[\"dolor\"]}");
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL);
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId(MEDICO);
        Medico medico = new Medico();
        medico.setUsuarioAuth(usuario);
        sesion = new SesionAtencionMedica();
        sesion.setId(SESION);
        sesion.setEstado(EstadoSesionMedica.ACTIVA);
        sesion.setHospital(hospital);
        sesion.setMedico(medico);
        atencion = new AtencionMedica();
        atencion.setId(ATENCION);
        atencion.setConsultaMedica(consulta);
        atencion.setSesionAtencionMedica(sesion);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4})
    void escalaSancionesYConservaEstadoYPrioridad(long cantidad) {
        permitirRegistro();
        Instant ultimaFecha = Instant.parse("2026-08-31T20:00:00Z");
        simularMarcasVigentes(cantidad, ultimaFecha);

        assertEquals(new DiscrepanciaSintomasDTO(true, false), service.registrar(MEDICO, SESION, CONSULTA));

        ArgumentCaptor<DiscrepanciaSintomas> registro = ArgumentCaptor.forClass(DiscrepanciaSintomas.class);
        verify(repoDiscrepancias).save(registro.capture());
        assertEquals(DNI, registro.getValue().getDni());
        assertEquals(EstadoDiscrepanciaSintomas.VIGENTE, registro.getValue().getEstado());
        assertEquals(atencion, registro.getValue().getAtencionMedica());
        SancionDigitalPaciente sancion = capturarSancion();
        assertEquals(cantidad, sancion.getCantidadVigentes());
        assertEquals(ultimaFecha, sancion.getFechaInicio());
        assertEquals(cantidad >= 3, sancion.isPermanente());
        assertEquals(fechaFin(ultimaFecha, cantidad), sancion.getFechaFin());
        assertEquals(TipoAvisoSancion.APLICADA, sancion.getTipoAviso());
        assertNotNull(sancion.getAvisoId());
        assertEquals(NivelDeGravedad.URGENTE, consulta.getNivelDeGravedadBot());
        assertEquals(NivelDeGravedad.NORMAL, consulta.getNivelDeGravedadMedico());
        assertEquals(EstadoConsulta.EN_ATENCION, consulta.getEstadoConsulta());
        verify(repoAuditorias, times(2)).save(any());
    }

    @Test
    void venceSinJobYEmiteAvisoEstableDeLevantamiento() {
        Instant inicio = Instant.now().minus(400, ChronoUnit.DAYS);
        SancionDigitalPaciente sancion = sancionActual(false, inicio, fechaFin(inicio, 1));
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI)).thenReturn(Optional.of(sancion));

        EstadoSancionPacienteDTO estado = service.estado(paciente);

        assertFalse(estado.vigente());
        assertFalse(estado.permanente());
        assertNull(estado.fechaFin());
        assertEquals(TipoAvisoSancion.LEVANTADA, estado.tipoAviso());
        assertEquals(estado.avisoId(), service.estado(paciente).avisoId());
    }

    @Test
    void usaDniDelRegistroDeLaCuentaAunqueElPerfilSeaEditable() {
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setTipoDocumento(TipoDocumento.DNI);
        usuario.setNumeroDocumento("12.345.678");
        paciente.setUsuarioAuth(usuario);
        paciente.setTipoDocumento(TipoDocumento.LIBRETA_CIVICA);
        paciente.setNumeroDocumento("11222333");
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI))
                .thenReturn(Optional.of(sancionActual(true, Instant.now(), null)));

        EstadoSancionPacienteDTO estado = service.estadoParaIngresoDigital(paciente);

        assertTrue(estado.vigente());
        assertTrue(estado.permanente());
        assertNull(estado.fechaFin());
    }

    @Test
    void noAsociaOtroTipoDeDocumentoConLaSancionDni() {
        paciente.setTipoDocumento(TipoDocumento.LIBRETA_CIVICA);

        assertFalse(service.estado(paciente).vigente());
        verifyNoInteractions(repoSanciones);
    }

    @Test
    void reintentarLaMarcaNoDuplicaLaSancionInclusoSiFueAnulada() {
        permitirRegistro();
        DiscrepanciaSintomas previa = discrepancia(Instant.now());
        previa.setEstado(EstadoDiscrepanciaSintomas.ANULADA);
        when(repoDiscrepancias.findByAtencionMedicaId(ATENCION)).thenReturn(Optional.of(previa));

        assertEquals(new DiscrepanciaSintomasDTO(true, false), service.registrar(MEDICO, SESION, CONSULTA));
        verify(repoDiscrepancias, never()).save(any());
        verifyNoInteractions(repoSanciones, repoAuditorias, jdbcTemplate);
    }

    @Test
    void rechazaLaMarcaSinDeclaracionPrevia() {
        permitirRegistro();
        consulta.setResumenPretriageJson(null);

        assertThrows(ConflictoDeEstadoException.class, () -> service.registrar(MEDICO, SESION, CONSULTA));
        verifyNoInteractions(repoSanciones, repoAuditorias, jdbcTemplate);
    }

    @Test
    void rechazaRegistrarDesdeUnaSesionAjena() {
        assertThrows(AccessDeniedException.class, () -> service.registrar("otro-medico", SESION, CONSULTA));
        verifyNoInteractions(repoAtenciones, repoDiscrepancias, repoAuditorias);
    }

    @Test
    void rechazaRegistrarUnaConsultaAsignadaAOtraSesion() {
        when(repoSesiones.findByIdAndMedicoUsuarioAuthIdForUpdate(SESION, MEDICO)).thenReturn(Optional.of(sesion));

        assertThrows(RecursoNoEncontradoException.class, () -> service.registrar(MEDICO, SESION, CONSULTA));
        verifyNoInteractions(repoDiscrepancias, repoAuditorias);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void anularRecalculaConLaUltimaMarcaQuePermaneceVigente(long restantes) {
        DiscrepanciaSintomas anulada = discrepancia(Instant.now());
        UsuarioAuth actor = permitirAnulacion(anulada);
        Instant ultimaFecha = Instant.now().minus(10, ChronoUnit.DAYS);
        SancionDigitalPaciente anterior = sancionActual(restantes == 2, ultimaFecha,
                restantes == 2 ? null : fechaFin(ultimaFecha, restantes + 1));
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI)).thenReturn(Optional.of(anterior));
        simularMarcasVigentes(restantes, ultimaFecha);

        service.anular(ADMIN, HOSPITAL, DISCREPANCIA);

        assertEquals(EstadoDiscrepanciaSintomas.ANULADA, anulada.getEstado());
        assertEquals(actor, anulada.getAnuladaPor());
        assertNotNull(anulada.getAnuladaEn());
        SancionDigitalPaciente posterior = capturarSancion();
        assertEquals(restantes, posterior.getCantidadVigentes());
        assertFalse(posterior.isPermanente());
        assertEquals(restantes == 0 ? null : ultimaFecha, posterior.getFechaInicio());
        assertEquals(fechaFin(ultimaFecha, restantes), posterior.getFechaFin());
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI)).thenReturn(Optional.of(posterior));
        EstadoSancionPacienteDTO estado = service.estadoParaIngresoDigital(paciente);
        assertEquals(restantes > 0, estado.vigente());
        assertEquals(restantes == 0 ? TipoAvisoSancion.LEVANTADA : TipoAvisoSancion.APLICADA, estado.tipoAviso());
        verify(repoAuditorias, times(2)).save(any());
    }

    @Test
    void alAnularLaMarcaRecienteLevantaLaSancionSiLaMarcaRestanteYaVencio() {
        DiscrepanciaSintomas anulada = discrepancia(Instant.now());
        permitirAnulacion(anulada);
        Instant anteriorInicio = Instant.now().minus(1, ChronoUnit.DAYS);
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI))
                .thenReturn(Optional.of(sancionActual(false, anteriorInicio, fechaFin(anteriorInicio, 2))));
        Instant ultimaFecha = Instant.now().minus(400, ChronoUnit.DAYS);
        simularMarcasVigentes(1, ultimaFecha);

        service.anular(ADMIN, HOSPITAL, DISCREPANCIA);

        SancionDigitalPaciente posterior = capturarSancion();
        assertEquals(fechaFin(ultimaFecha, 1), posterior.getFechaFin());
        when(repoSanciones.findFirstByDniOrderByIdDesc(DNI)).thenReturn(Optional.of(posterior));
        EstadoSancionPacienteDTO estado = service.estadoParaIngresoDigital(paciente);
        assertFalse(estado.vigente());
        assertEquals(TipoAvisoSancion.LEVANTADA, estado.tipoAviso());
    }

    @Test
    void exigePermisosDelHospitalAntesDeAnular() {
        when(staffAccessService.exigirAdminHospital(ADMIN, HOSPITAL)).thenThrow(new AccessDeniedException("Ajeno"));

        assertThrows(AccessDeniedException.class, () -> service.anular(ADMIN, HOSPITAL, DISCREPANCIA));
        verifyNoInteractions(repoDiscrepancias);
    }

    @Test
    void anularNoEncuentraMarcasDeOtroHospital() {
        assertThrows(RecursoNoEncontradoException.class, () -> service.anular(ADMIN, HOSPITAL, DISCREPANCIA));
        verify(repoDiscrepancias).findByIdAndAtencionMedicaSesionAtencionMedicaHospitalId(DISCREPANCIA, HOSPITAL);
        verifyNoInteractions(repoSanciones, repoAuditorias);
    }

    @Test
    void impideAnularDosVecesLaMismaMarca() {
        DiscrepanciaSintomas anulada = discrepancia(Instant.now());
        anulada.setEstado(EstadoDiscrepanciaSintomas.ANULADA);
        permitirAnulacion(anulada);

        assertThrows(ConflictoDeEstadoException.class, () -> service.anular(ADMIN, HOSPITAL, DISCREPANCIA));
        verifyNoInteractions(repoSanciones, repoAuditorias);
    }

    @Test
    void rechazaAnularUnaMarcaYaAnuladaAunqueElContextoTengaEstadoViejo() {
        prepararAnulacion(discrepancia(Instant.now()));
        when(repoDiscrepancias.existsByIdAndEstado(DISCREPANCIA, EstadoDiscrepanciaSintomas.VIGENTE))
                .thenReturn(false);

        assertThrows(ConflictoDeEstadoException.class, () -> service.anular(ADMIN, HOSPITAL, DISCREPANCIA));
        verifyNoInteractions(repoSanciones, repoAuditorias);
    }

    private void permitirRegistro() {
        when(repoSesiones.findByIdAndMedicoUsuarioAuthIdForUpdate(SESION, MEDICO)).thenReturn(Optional.of(sesion));
        when(repoAtenciones.findByConsultaMedicaIdAndSesionAtencionMedicaIdAndEstado(
                CONSULTA, SESION, EstadoAtencionMedica.EN_CURSO)).thenReturn(Optional.of(atencion));
    }

    private UsuarioAuth permitirAnulacion(DiscrepanciaSintomas discrepancia) {
        UsuarioAuth actor = prepararAnulacion(discrepancia);
        if (discrepancia.getEstado() == EstadoDiscrepanciaSintomas.VIGENTE) {
            when(repoDiscrepancias.existsByIdAndEstado(DISCREPANCIA, EstadoDiscrepanciaSintomas.VIGENTE))
                    .thenReturn(true);
        }
        return actor;
    }

    private UsuarioAuth prepararAnulacion(DiscrepanciaSintomas discrepancia) {
        UsuarioAuth actor = new UsuarioAuth();
        actor.setId(ADMIN);
        when(staffAccessService.exigirAdminHospital(ADMIN, HOSPITAL)).thenReturn(actor);
        when(repoDiscrepancias.findByIdAndAtencionMedicaSesionAtencionMedicaHospitalId(DISCREPANCIA, HOSPITAL))
                .thenReturn(Optional.of(discrepancia));
        return actor;
    }

    private void simularMarcasVigentes(long cantidad, Instant ultimaFecha) {
        when(repoDiscrepancias.countByDniAndEstado(DNI, EstadoDiscrepanciaSintomas.VIGENTE)).thenReturn(cantidad);
        if (cantidad > 0) {
            when(repoDiscrepancias.findFirstByDniAndEstadoOrderByRegistradaEnDescIdDesc(
                    DNI, EstadoDiscrepanciaSintomas.VIGENTE)).thenReturn(Optional.of(discrepancia(ultimaFecha)));
        }
    }

    private DiscrepanciaSintomas discrepancia(Instant fecha) {
        DiscrepanciaSintomas registro = new DiscrepanciaSintomas();
        registro.setId(DISCREPANCIA);
        registro.setEstado(EstadoDiscrepanciaSintomas.VIGENTE);
        registro.setDni(DNI);
        registro.setRegistradaEn(fecha);
        registro.setAtencionMedica(atencion);
        return registro;
    }

    private SancionDigitalPaciente capturarSancion() {
        ArgumentCaptor<SancionDigitalPaciente> captor = ArgumentCaptor.forClass(SancionDigitalPaciente.class);
        verify(repoSanciones).save(captor.capture());
        return captor.getValue();
    }

    private static SancionDigitalPaciente sancionActual(boolean permanente, Instant inicio, Instant fin) {
        SancionDigitalPaciente sancion = new SancionDigitalPaciente();
        sancion.setId(1L);
        sancion.setPermanente(permanente);
        sancion.setFechaInicio(inicio);
        sancion.setCalculadaEn(inicio);
        sancion.setFechaFin(fin);
        sancion.setAvisoId("aplicada-1");
        sancion.setTipoAviso(TipoAvisoSancion.APLICADA);
        return sancion;
    }

    private static Instant fechaFin(Instant inicio, long cantidad) {
        var fecha = inicio.atZone(ZoneId.of("America/Argentina/Buenos_Aires"));
        if (cantidad == 1) {
            return fecha.plusMonths(6).toInstant();
        }
        if (cantidad == 2) {
            return fecha.plusYears(1).toInstant();
        }
        return null;
    }
}
