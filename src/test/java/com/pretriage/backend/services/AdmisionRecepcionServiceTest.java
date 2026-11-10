package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CrearAdmisionRecepcionRequest;
import com.pretriage.backend.controllers.dtos.FormularioTriageRecepcionRequest;
import com.pretriage.backend.controllers.dtos.DolorReportadoRequest;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.consultas.*;
import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.MembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import com.pretriage.backend.model.hospitales.*;
import com.pretriage.backend.model.personas.*;
import com.pretriage.backend.model.recepcion.*;
import com.pretriage.backend.repositories.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdmisionRecepcionServiceTest {
    @Mock RepoRecepcionistas repoRecepcionistas; @Mock RepoHospitales repoHospitales;
    @Mock RepoMembresiasHospital repoMembresiasHospital;
    @Mock RepoSesionesRecepcion repoSesionesRecepcion; @Mock RepoAdmisionesRecepcion repoAdmisionesRecepcion;
    @Mock RepoPacientes repoPacientes; @Mock RepoConsultasMedicas repoConsultasMedicas;
    @Mock RepoDirecciones repoDirecciones;
    @Mock RepoCredenciales repoCredenciales;
    @Mock RepoEspecialidadesMedicas repoEspecialidadesMedicas; @Mock TriageFormularioService triageFormularioService;
    @Mock IngresoColaService ingresoColaService; @Mock EstimacionAtencionService estimacionAtencionService;
    @Mock AsignacionSectorService asignacionSectorService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks AdmisionRecepcionService service;

    @Test
    void informaAtencionEnCursoAlBuscarPaciente() {
        Hospital hospital = new Hospital(); hospital.setId(3L);
        SesionRecepcion sesion = new SesionRecepcion(); sesion.setId(6L);
        sesion.setHospital(hospital); sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        Paciente paciente = new Paciente(); paciente.setId(2L); paciente.setNumeroDocumento("30111222");
        paciente.setNombre("Ana"); paciente.setApellido("Perez");
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(sesion));
        when(repoPacientes.findByNumeroDocumentoOrUsuarioAuthNumeroDocumento("30111222", "30111222"))
                .thenReturn(Optional.of(paciente));
        when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(2L), anyCollection()))
                .thenReturn(Optional.of(consulta));

        var resultado = service.buscarPaciente("auth", 6L, "30111222");

        assertTrue(resultado.atencionEnCurso());
        assertEquals(EstadoConsulta.EN_COLA, resultado.estadoAtencionEnCurso());
    }

    @Test
    void noPermiteDosSesionesActivas() {
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(1L);
        Hospital hospital = new Hospital(); hospital.setId(10L);
        when(repoRecepcionistas.findRecepcionistaByUsuarioAuthId("auth")).thenReturn(Optional.of(recepcionista));
        when(repoHospitales.findByIdForUpdate(10L)).thenReturn(Optional.of(hospital));
        when(repoHospitales.findByIdAndRecepcionistasId(10L, 1L)).thenReturn(Optional.of(hospital));
        when(repoSesionesRecepcion.existsByRecepcionistaIdAndEstado(1L, EstadoSesionRecepcion.ACTIVA)).thenReturn(true);
        assertThrows(com.pretriage.backend.exceptions.ConflictoDeEstadoException.class,
                () -> service.iniciarSesion("auth", 10L));
    }

    @Test
    void noIniciaSesionSiMembresiaDelHospitalEstaSuspendida() {
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(1L);
        Hospital hospital = new Hospital(); hospital.setId(10L);
        MembresiaHospital membresia = new MembresiaHospital();
        membresia.setEstado(EstadoMembresiaHospital.SUSPENDIDA);
        membresia.setRoles(java.util.Set.of(RolMembresiaHospital.RECEPCIONISTA));
        when(repoRecepcionistas.findRecepcionistaByUsuarioAuthId("auth")).thenReturn(Optional.of(recepcionista));
        when(repoHospitales.findByIdForUpdate(10L)).thenReturn(Optional.of(hospital));
        when(repoHospitales.findByIdAndRecepcionistasId(10L, 1L)).thenReturn(Optional.of(hospital));
        when(repoMembresiasHospital.findByUsuarioIdAndHospitalId("auth", 10L)).thenReturn(Optional.of(membresia));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.iniciarSesion("auth", 10L));

        verify(repoSesionesRecepcion, never()).save(any());
    }

    @Test
    void finalizaFormularioEIngresaLaMismaConsultaALaCola() throws Exception {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        Hospital hospital = new Hospital(); hospital.setId(3L);
        EspecialidadMedica especialidad = new EspecialidadMedica(); especialidad.setId(4L);
        Sector sector = new Sector(); sector.setId(10L); sector.setNombre("Pediatria");
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setHospital(hospital); consulta.setEspecialidad(especialidad); consulta.setSector(sector);
        consulta.setCodigoLlamado("R-ABC123");
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(1L);
        SesionRecepcion sesion = new SesionRecepcion(); sesion.setId(6L); sesion.setRecepcionista(recepcionista);
        sesion.setHospital(hospital); sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        AdmisionRecepcion admision = new AdmisionRecepcion(); admision.setId(7L); admision.setConsultaMedica(consulta);
        admision.setSesionRecepcion(sesion); admision.setEstado(EstadoAdmisionRecepcion.INICIADA);
        FormularioTriageRecepcionRequest formulario = new FormularioTriageRecepcionRequest(
                "dolor abdominal", List.of(), "hace dos horas", "empeora",
                List.of(new DolorReportadoRequest("abdomen", 7)), false,
                List.of(), List.of(), List.of(), List.of(), "no aplica", "");
        TriageResultDTO resultado = new TriageResultDTO("dolor abdominal", List.of("dolor"), "dos horas",
                "empeora", 7, List.of(), List.of(), List.of(), List.of(), "no aplica", "", 3, false, "evaluar");
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(triageFormularioService.clasificar(formulario)).thenReturn(resultado);
        when(ingresoColaService.ingresar(consulta, NivelDeGravedad.URGENTE)).thenAnswer(inv -> {
            consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE); consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
            return new TiempoEstimadoAtencionResponse();
        });

        var dto = service.finalizar("auth", 7L, formulario);

        assertEquals(EstadoAdmisionRecepcion.FINALIZADA, admision.getEstado());
        assertEquals(NivelDeGravedad.URGENTE, dto.prioridad());
        assertEquals(10L, dto.sectorId());
        assertEquals("Pediatria", dto.nombreSector());
        verify(ingresoColaService).ingresar(consulta, NivelDeGravedad.URGENTE);
    }

    @Test
    void falloGeminiEnRecepcionConservaFallbackYFinalizaLaAdmision() throws Exception {
        Paciente paciente = new Paciente(); paciente.setId(2L);
        Hospital hospital = new Hospital(); hospital.setId(3L);
        EspecialidadMedica especialidad = new EspecialidadMedica(); especialidad.setId(4L);
        Sector sector = new Sector(); sector.setId(10L); sector.setNombre("Pediatria");
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setHospital(hospital); consulta.setEspecialidad(especialidad); consulta.setSector(sector);
        consulta.setCodigoLlamado("R-ABC123");
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(1L);
        SesionRecepcion sesion = new SesionRecepcion(); sesion.setId(6L); sesion.setRecepcionista(recepcionista);
        sesion.setHospital(hospital); sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        AdmisionRecepcion admision = new AdmisionRecepcion(); admision.setId(7L); admision.setConsultaMedica(consulta);
        admision.setSesionRecepcion(sesion); admision.setEstado(EstadoAdmisionRecepcion.INICIADA);
        FormularioTriageRecepcionRequest formulario = new FormularioTriageRecepcionRequest(
                "dolor abdominal", List.of(), "hace dos horas", "empeora",
                List.of(new DolorReportadoRequest("abdomen", 7)), false,
                List.of(), List.of(), List.of(), List.of(), "no aplica", "");
        TriageResultDTO resultado = new TriageResultDTO("dolor abdominal", List.of("dolor"), "dos horas",
                "empeora", 7, List.of(), List.of(), List.of(), List.of(), "no aplica", "", 3, false, "evaluar");
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(triageFormularioService.clasificar(formulario))
                .thenThrow(new com.pretriage.backend.exceptions.ProveedorIaException(null));
        when(triageFormularioService.resultadoFallback(formulario)).thenReturn(resultado);
        when(ingresoColaService.ingresar(consulta, NivelDeGravedad.URGENTE)).thenAnswer(inv -> {
            consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE); consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
            return new TiempoEstimadoAtencionResponse();
        });

        var dto = service.finalizar("auth", 7L, formulario);

        assertEquals(EstadoAdmisionRecepcion.FINALIZADA, admision.getEstado());
        assertEquals(NivelDeGravedad.URGENTE, dto.prioridad());
        assertEquals(10L, dto.sectorId());
        assertEquals("Pediatria", dto.nombreSector());
        verify(triageFormularioService).resultadoFallback(formulario);
        verify(ingresoColaService).ingresar(consulta, NivelDeGravedad.URGENTE);
    }

    @Test
    void cancelaAdmisionYConsultaSinIngresarALaCola() {
        AdmisionRecepcion admision = admisionAbierta();
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(admision.getSesionRecepcion()));

        var resultado = service.cancelar("auth", 7L);

        assertEquals(EstadoAdmisionRecepcion.CANCELADA, resultado.estado());
        assertEquals(EstadoConsulta.CANCELADA, admision.getConsultaMedica().getEstadoConsulta());
        assertNotNull(admision.getFechaHoraCancelacion());
        verify(repoConsultasMedicas).save(admision.getConsultaMedica());
        verify(repoAdmisionesRecepcion).save(admision);
        verifyNoInteractions(ingresoColaService);
    }

    @Test
    void noCancelaAdmisionFinalizada() {
        AdmisionRecepcion admision = admisionAbierta();
        admision.setEstado(EstadoAdmisionRecepcion.FINALIZADA);
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(admision.getSesionRecepcion()));

        assertThrows(com.pretriage.backend.exceptions.ConflictoDeEstadoException.class,
                () -> service.cancelar("auth", 7L));
    }

    @Test
    void listaAdmisionesAbiertasEnOrdenDelRepositorio() {
        AdmisionRecepcion admision = admisionAbierta();
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(admision.getSesionRecepcion()));
        when(repoAdmisionesRecepcion.findBySesionRecepcionIdAndEstadoInOrderByFechaHoraInicioAsc(
                eq(6L), anyCollection())).thenReturn(List.of(admision));

        var resultado = service.listarAbiertas("auth", 6L);

        assertEquals(1, resultado.size());
        assertEquals(7L, resultado.getFirst().id());
        assertNull(resultado.getFirst().estimacion());
    }

    @Test
    void recuperaDetalleFinalizadoConEstimacionDinamica() {
        AdmisionRecepcion admision = admisionAbierta();
        admision.setEstado(EstadoAdmisionRecepcion.FINALIZADA);
        admision.getConsultaMedica().setEstadoConsulta(EstadoConsulta.EN_COLA);
        TiempoEstimadoAtencionResponse estimacion = new TiempoEstimadoAtencionResponse();
        when(repoAdmisionesRecepcion.existsById(7L)).thenReturn(true);
        when(repoAdmisionesRecepcion.findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(estimacionAtencionService.calcularPara(admision.getConsultaMedica())).thenReturn(estimacion);

        var resultado = service.obtenerDetalle("auth", 7L);

        assertSame(estimacion, resultado.estimacion());
    }

    @Test
    void guardaCoberturaEnAdmisionYPermiteContinuarSinCobertura() {
        AdmisionRecepcion admision = admisionAbierta();
        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setNombre("OSDE");
        Credencial credencial = new Credencial();
        credencial.setObraSocial(obraSocial);
        credencial.setFechaVencimiento(LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")).plusDays(1));
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(repoAdmisionesRecepcion.existsById(7L)).thenReturn(true);
        when(repoAdmisionesRecepcion.findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(repoCredenciales.findByIdAndPacienteId(9L, 2L)).thenReturn(Optional.of(credencial));

        var seleccion = service.seleccionarCobertura("auth", 7L, 9L);
        var detalle = service.obtenerDetalle("auth", 7L);
        assertEquals(9L, seleccion.credencialId());
        assertEquals("OSDE", seleccion.nombreObraSocial());
        assertTrue(detalle.coberturaConfirmada());
        assertEquals(seleccion, detalle.cobertura());

        service.seleccionarCobertura("auth", 7L, null);
        var sinCobertura = service.obtenerDetalle("auth", 7L);
        assertTrue(sinCobertura.coberturaConfirmada());
        assertNull(sinCobertura.cobertura());
    }

    @Test
    void rechazaCoberturaAjenaVencidaODeAdmisionFinalizada() {
        AdmisionRecepcion admision = admisionAbierta();
        Credencial credencial = new Credencial();
        credencial.setFechaVencimiento(LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")));
        when(repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(7L, "auth"))
                .thenReturn(Optional.of(admision));
        when(repoCredenciales.findByIdAndPacienteId(8L, 2L)).thenReturn(Optional.empty());
        when(repoCredenciales.findByIdAndPacienteId(9L, 2L)).thenReturn(Optional.of(credencial));

        assertThrows(RecursoNoEncontradoException.class, () -> service.seleccionarCobertura("auth", 7L, 8L));
        assertThrows(IllegalArgumentException.class, () -> service.seleccionarCobertura("auth", 7L, 9L));
        admision.setEstado(EstadoAdmisionRecepcion.FINALIZADA);
        assertThrows(ConflictoDeEstadoException.class, () -> service.seleccionarCobertura("auth", 7L, null));
        assertNull(admision.getCoberturaConfirmada());
    }

    @Test
    void registraDatosPersonalesSinCorreoNiCodigoPostalDelPacientePresencial() {
        Hospital hospital = new Hospital(); hospital.setId(3L); hospital.setNombre("Hospital");
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(4L); especialidad.setCodigo("CLINICA_MEDICA");
        hospital.setEspecialidades(List.of(especialidad));
        SesionRecepcion sesion = new SesionRecepcion(); sesion.setId(6L);
        sesion.setHospital(hospital); sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        var request = new CrearAdmisionRecepcionRequest(
                6L, "30111222", "Ana", "Perez", LocalDate.of(1990, 5, 10),
                Genero.FEMENINO, "+54 11 5555-0101", "", "Calle E2E", "1234", "2",
                "Ciudad Autonoma de Buenos Aires", "Buenos Aires", null, "CLINICA_MEDICA");
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(sesion));
        when(repoPacientes.findByNumeroDocumentoOrUsuarioAuthNumeroDocumento("30111222", "30111222"))
                .thenReturn(Optional.empty());
        when(repoDirecciones.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repoPacientes.save(any())).thenAnswer(inv -> {
            Paciente paciente = inv.getArgument(0); paciente.setId(2L); return paciente;
        });
        when(repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(eq(2L), anyCollection()))
                .thenReturn(Optional.empty());
        when(repoEspecialidadesMedicas.findByCodigo("CLINICA_MEDICA")).thenReturn(Optional.of(especialidad));
        when(repoConsultasMedicas.save(any())).thenAnswer(inv -> {
            ConsultaMedica consulta = inv.getArgument(0); consulta.setId(5L); return consulta;
        });
        when(asignacionSectorService.asignarSector(any(ConsultaMedica.class))).thenAnswer(inv -> {
            ConsultaMedica consulta = inv.getArgument(0);
            Sector sector = new Sector(); sector.setId(50L); sector.setNombre("Sector A");
            consulta.setSector(sector);
            return sector;
        });
        when(repoAdmisionesRecepcion.save(any())).thenAnswer(inv -> {
            AdmisionRecepcion admision = inv.getArgument(0); admision.setId(7L); return admision;
        });

        service.crearAdmision("auth", request);

        var consultaCaptor = org.mockito.ArgumentCaptor.forClass(ConsultaMedica.class);
        verify(repoConsultasMedicas).save(consultaCaptor.capture());
        assertEquals("Sector A", consultaCaptor.getValue().getSector().getNombre());
        verify(asignacionSectorService).asignarSector(consultaCaptor.getValue());

        var pacienteCaptor = org.mockito.ArgumentCaptor.forClass(Paciente.class);
        verify(repoPacientes).save(pacienteCaptor.capture());
        Paciente paciente = pacienteCaptor.getValue();
        assertEquals("+54 11 5555-0101", paciente.getTelefono());
        assertNull(paciente.getCorreoElectronico());
        assertEquals("Calle E2E", paciente.getDireccion().getCalle());
        assertEquals("1234", paciente.getDireccion().getAltura());
        assertEquals("Ciudad Autonoma de Buenos Aires", paciente.getDireccion().getCiudad());
        assertEquals("Buenos Aires", paciente.getDireccion().getProvincia());
        assertNull(paciente.getDireccion().getCodigoPostal());
    }

    @Test
    void actualizaDatosDePacienteReutilizadoSinCambiarLaIdentidadDeSuCuenta() {
        AdmisionRecepcion anterior = admisionAbierta();
        ConsultaMedica consultaAnterior = anterior.getConsultaMedica();
        EspecialidadMedica especialidad = consultaAnterior.getEspecialidad();
        consultaAnterior.getHospital().setEspecialidades(List.of(especialidad));
        Paciente paciente = consultaAnterior.getPaciente();
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setNombre("Ana");
        usuario.setCorreoElectronico("cuenta@example.com");
        paciente.setUsuarioAuth(usuario);
        paciente.setCorreoElectronico("anterior@example.com");
        Direccion direccion = new Direccion();
        direccion.setCodigoPostal("1414");
        paciente.setDireccion(direccion);
        LocalDate nacimiento = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")).minusYears(120);
        var request = new CrearAdmisionRecepcionRequest(
                6L, "30111222", "Ana María", "Pérez", nacimiento, Genero.FEMENINO,
                "+54 11 5555-0101", "", "Calle Nueva", "1234", null,
                "Palermo", "Ciudad Autónoma de Buenos Aires", "", "CLINICA_MEDICA");
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(anterior.getSesionRecepcion()));
        when(repoPacientes.findByNumeroDocumentoOrUsuarioAuthNumeroDocumento("30111222", "30111222"))
                .thenReturn(Optional.of(paciente));
        when(repoEspecialidadesMedicas.findByCodigo("CLINICA_MEDICA")).thenReturn(Optional.of(especialidad));
        when(repoDirecciones.save(direccion)).thenReturn(direccion);
        when(repoAdmisionesRecepcion.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var admision = service.crearAdmision("auth", request);
        var datos = service.buscarPaciente("auth", 6L, "30111222");

        assertEquals(paciente.getId(), admision.pacienteId());
        assertEquals("Ana María", datos.nombre());
        assertEquals("Pérez", datos.apellido());
        assertEquals(nacimiento, datos.fechaNacimiento());
        assertEquals(Genero.FEMENINO, datos.generoBiologico());
        assertEquals("+54 11 5555-0101", datos.telefono());
        assertNull(datos.correoElectronico());
        assertNull(datos.codigoPostal());
        assertEquals("Calle Nueva", datos.calle());
        assertEquals("Ana", usuario.getNombre());
        assertEquals("cuenta@example.com", usuario.getCorreoElectronico());
        verify(repoPacientes, never()).save(any());
    }

    @Test
    void noCreaAdmisionConFechaDeNacimientoDeHoyODeHaceMasDe120Anios() {
        when(repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(6L, "auth"))
                .thenReturn(Optional.of(admisionAbierta().getSesionRecepcion()));
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"));

        for (LocalDate nacimiento : List.of(hoy, hoy.minusYears(200))) {
            var request = new CrearAdmisionRecepcionRequest(
                    6L, "30111222", "Ana", "Pérez", nacimiento, Genero.FEMENINO,
                    "+54 11 5555-0101", null, "Calle", "1234", null,
                    "Palermo", "Ciudad Autónoma de Buenos Aires", null, "CLINICA_MEDICA");

            assertThrows(IllegalArgumentException.class, () -> service.crearAdmision("auth", request));
        }

        verifyNoInteractions(repoPacientes, repoDirecciones, repoAdmisionesRecepcion);
    }

    private AdmisionRecepcion admisionAbierta() {
        Paciente paciente = new Paciente();
        paciente.setId(2L); paciente.setNumeroDocumento("30111222");
        paciente.setNombre("Ana"); paciente.setApellido("Perez");
        Hospital hospital = new Hospital(); hospital.setId(3L); hospital.setNombre("Hospital");
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setId(4L); especialidad.setCodigo("CLINICA_MEDICA"); especialidad.setNombre("Clinica");
        ConsultaMedica consulta = new ConsultaMedica(); consulta.setId(5L); consulta.setPaciente(paciente);
        consulta.setHospital(hospital); consulta.setEspecialidad(especialidad); consulta.setCodigoLlamado("R-ABC123");
        consulta.setEstadoConsulta(EstadoConsulta.PRETRIAGE_EN_PROCESO);
        SesionRecepcion sesion = new SesionRecepcion(); sesion.setId(6L); sesion.setHospital(hospital);
        sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        AdmisionRecepcion admision = new AdmisionRecepcion(); admision.setId(7L);
        admision.setConsultaMedica(consulta); admision.setSesionRecepcion(sesion);
        admision.setEstado(EstadoAdmisionRecepcion.INICIADA);
        return admision;
    }
}
