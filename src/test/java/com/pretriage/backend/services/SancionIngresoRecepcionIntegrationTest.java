package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CrearAdmisionRecepcionRequest;
import com.pretriage.backend.controllers.dtos.FormularioTriageRecepcionRequest;
import com.pretriage.backend.controllers.dtos.RevisionPrioridadRequest;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.exceptions.PacienteSancionadoException;
import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.DecisionRevisionPrioridad;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Genero;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.Recepcionista;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.model.recepcion.EstadoAdmisionRecepcion;
import com.pretriage.backend.model.recepcion.EstadoSesionRecepcion;
import com.pretriage.backend.model.recepcion.SesionRecepcion;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class SancionIngresoRecepcionIntegrationTest {
    private static final String DNI = "32684519";
    private static final String ESPECIALIDAD = "SANCION_TEST";
    private static final String MEDICO = "sancion-test-medico";
    private static final String RECEPCION = "sancion-test-recepcion";
    private static final String PACIENTE = "sancion-test-paciente";

    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired DiscrepanciaSintomasService discrepancias;
    @Autowired AtencionMedicoService atencionMedico;
    @Autowired AtencionHospitalService atencionHospital;
    @Autowired AdmisionRecepcionService recepcion;
    @MockitoBean TriageFormularioService triageFormulario;

    @Test
    void restringeSoloNuevosIngresosRemotosYConservaLaAtencionYElIngresoPresencial() {
        Paciente paciente = paciente();
        SesionAtencionMedica sesionMedica = sesionMedica();
        ConsultaMedica consulta = atencionEnCurso(paciente, sesionMedica);
        var marca = discrepancias.registrar(MEDICO, sesionMedica.getId(), consulta.getId());

        assertTrue(marca.registrada());
        assertTrue(discrepancias.estado(paciente).vigente());
        assertEquals(EstadoConsulta.EN_ATENCION, consulta.getEstadoConsulta());
        assertEquals(NivelDeGravedad.URGENTE, consulta.getNivelDeGravedadBot());
        assertNull(consulta.getNivelDeGravedadMedico());

        atencionMedico.revisarPrioridad(MEDICO, sesionMedica.getId(), consulta.getId(),
                new RevisionPrioridadRequest(DecisionRevisionPrioridad.CONFIRMAR, null, null));
        atencionMedico.finalizarConsulta(MEDICO, sesionMedica.getId(), consulta.getId());
        assertEquals(EstadoConsulta.FINALIZADA, consulta.getEstadoConsulta());

        SesionRecepcion sesionRecepcion = sesionRecepcion(sesionMedica.getHospital());
        var admision = recepcion.crearAdmision(RECEPCION, new CrearAdmisionRecepcionRequest(
                sesionRecepcion.getId(), DNI, "Ana", "Prueba", LocalDate.of(1992, 1, 1),
                Genero.FEMENINO, "1155555555", null, "Calle de prueba", "123", null,
                "Buenos Aires", "Buenos Aires", null, ESPECIALIDAD));
        assertEquals(paciente.getId(), admision.pacienteId());

        var formulario = new FormularioTriageRecepcionRequest("Síntomas graves", List.of(), "Hoy",
                "Grave", List.of(), false, List.of(), List.of(), List.of(), List.of(), "", "");
        when(triageFormulario.clasificar(formulario)).thenReturn(new TriageResultDTO(
                "Síntomas graves", List.of("dolor"), "Hoy", "Grave", 9, List.of(), List.of(),
                List.of(), List.of(), "", "", 5, true, "Atención inmediata"));
        var ingreso = recepcion.finalizar(RECEPCION, admision.id(), formulario);
        assertEquals(EstadoAdmisionRecepcion.FINALIZADA, ingreso.estado());
        assertEquals(NivelDeGravedad.RIESGO_VITAL_INMEDIATO, ingreso.prioridad());

        assertThrows(PacienteSancionadoException.class,
                () -> atencionHospital.seleccionarHospital(PACIENTE, "hospital-sancion", ESPECIALIDAD));
    }

    private Paciente paciente() {
        UsuarioAuth usuario = usuario(PACIENTE);
        usuario.setTipoDocumento(TipoDocumento.DNI);
        usuario.setNumeroDocumento(DNI);
        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);
        paciente.setNombre("Ana");
        paciente.setApellido("Prueba");
        paciente.setTipoDocumento(TipoDocumento.DNI);
        paciente.setNumeroDocumento(DNI);
        return persistir(paciente);
    }

    private SesionAtencionMedica sesionMedica() {
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setCodigo(ESPECIALIDAD);
        especialidad.setNombre("Clínica de prueba");
        persistir(especialidad);

        Hospital hospital = new Hospital();
        hospital.setNombre("Hospital de prueba");
        hospital.setPlaceId("hospital-sancion");
        hospital.getEspecialidades().add(especialidad);
        persistir(hospital);

        Sector sector = new Sector();
        sector.setNombre("Guardia");
        sector.setHospital(hospital);
        sector.setEspecialidad(especialidad);
        persistir(sector);
        Sala sala = new Sala();
        sala.setNombre("Consultorio 1");
        sala.setHospital(hospital);
        sala.setSector(sector);
        sala.setEspecialidad(especialidad);
        persistir(sala);
        sector.getSalas().add(sala);

        Medico medico = new Medico();
        medico.setUsuarioAuth(usuario(MEDICO));
        persistir(medico);
        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setEstado(EstadoSesionMedica.ACTIVA);
        sesion.setFechaHoraInicio(LocalDateTime.now());
        sesion.setMedico(medico);
        sesion.setHospital(hospital);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(sector);
        sesion.setSala(sala);
        return persistir(sesion);
    }

    private ConsultaMedica atencionEnCurso(Paciente paciente, SesionAtencionMedica sesion) {
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setEstadoConsulta(EstadoConsulta.EN_ATENCION);
        consulta.setFechaHoraCreacion(LocalDateTime.now());
        consulta.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
        consulta.setResumenPretriageJson(objectMapper.writeValueAsString(new TriageResultDTO(
                "Dolor abdominal", List.of("dolor"), "Hoy", "Intenso", 7, List.of(),
                List.of(), List.of(), List.of(), "", "", 3, false, "")));
        consulta.setPaciente(paciente);
        consulta.setMedico(sesion.getMedico());
        consulta.setHospital(sesion.getHospital());
        consulta.setEspecialidad(sesion.getEspecialidad());
        consulta.setSector(sesion.getSector());
        consulta.setSala(sesion.getSala());
        persistir(consulta);

        GestorDeCola gestor = new GestorDeCola();
        gestor.setHospital(sesion.getHospital());
        gestor.setEspecialidad(sesion.getEspecialidad());
        gestor.setSector(sesion.getSector());
        persistir(gestor);
        EntradaCola entrada = new EntradaCola();
        entrada.setConsultaMedica(consulta);
        entrada.setGestorDeCola(gestor);
        entrada.setEstado(EstadoEntradaCola.EN_ATENCION);
        entrada.setFechaHoraIngreso(LocalDateTime.now());
        entrada.setPrioridad(3);
        persistir(entrada);

        AtencionMedica atencion = new AtencionMedica();
        atencion.setConsultaMedica(consulta);
        atencion.setSesionAtencionMedica(sesion);
        atencion.setEstado(EstadoAtencionMedica.EN_CURSO);
        atencion.setFechaHoraInicio(LocalDateTime.now());
        persistir(atencion);
        return consulta;
    }

    private SesionRecepcion sesionRecepcion(Hospital hospital) {
        Recepcionista recepcionista = new Recepcionista();
        recepcionista.setUsuarioAuth(usuario(RECEPCION));
        persistir(recepcionista);
        SesionRecepcion sesion = new SesionRecepcion();
        sesion.setHospital(hospital);
        sesion.setRecepcionista(recepcionista);
        sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        sesion.setFechaHoraInicio(LocalDateTime.now());
        return persistir(sesion);
    }

    private UsuarioAuth usuario(String id) {
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId(id);
        return usuario;
    }

    private <T> T persistir(T entidad) {
        entityManager.persist(entidad);
        return entidad;
    }
}
