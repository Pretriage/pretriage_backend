package com.pretriage.backend.services;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoGestoresDeColas;
import com.pretriage.backend.repositories.RepoHospitales;
import com.pretriage.backend.repositories.RepoSalas;
import com.pretriage.backend.repositories.RepoSectores;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HospitalConfigurationService.class, SesionesMedicasAdminService.class})
class HospitalConfigurationPersistenceTest {
    private static final String ADMIN_ID = "auth0|sector-delete-test";

    @Autowired private TestEntityManager em;
    @Autowired private HospitalConfigurationService service;
    @Autowired private SesionesMedicasAdminService sesionesAdmin;
    @Autowired private RepoHospitales hospitales;
    @Autowired private RepoSectores sectores;
    @Autowired private RepoSalas salas;
    @Autowired private RepoGestoresDeColas gestores;
    @Autowired private RepoConsultasMedicas consultas;
    @Autowired private RepoSesionesAtencionMedica sesiones;
    @Autowired private RepoAuditoriasHospital auditorias;
    @MockitoBean private StaffAccessService staffAccess;

    private UsuarioAuth actor;
    private Hospital hospital;
    private Hospital otroHospital;
    private EspecialidadMedica especialidad;

    @BeforeEach
    void preparar() {
        actor = new UsuarioAuth();
        actor.setId(ADMIN_ID);
        actor.setNombre("Admin test");
        em.persist(actor);

        especialidad = new EspecialidadMedica();
        especialidad.setCodigo("SECTOR_DELETE_TEST");
        especialidad.setNombre("Especialidad test borrado");
        em.persist(especialidad);

        hospital = guardarHospital("sector-delete-hospital-a");
        otroHospital = guardarHospital("sector-delete-hospital-b");
        when(staffAccess.exigirAdminHospital(anyString(), anyLong())).thenReturn(actor);
    }

    @Test
    void eliminaSectorSalaYGestorDeColaVaciosDeLaBase() {
        Fixture fixture = guardarFixture(hospital, true);
        em.flush();
        Long sectorId = fixture.sector().getId();
        Long salaId = fixture.sala().getId();
        Long gestorId = fixture.gestor().getId();

        service.eliminarSector(ADMIN_ID, hospital.getId(), sectorId);
        em.flush();
        em.clear();

        assertTrue(sectores.findById(sectorId).isEmpty());
        assertTrue(salas.findById(salaId).isEmpty());
        assertTrue(gestores.findById(gestorId).isEmpty());
        assertEquals(2, auditorias.findAll().size());
    }

    @Test
    void consultaFinalizadaArchivaSectorYSalaYConservaHistorial() {
        Fixture fixture = guardarFixture(hospital, true);
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(hospital);
        consulta.setEspecialidad(especialidad);
        consulta.setSector(fixture.sector());
        consulta.setSala(fixture.sala());
        consulta.setEstadoConsulta(EstadoConsulta.FINALIZADA);
        em.persist(consulta);
        fixture.gestor().getConsultasEnEspera().add(consulta);
        em.flush();
        Long sectorId = fixture.sector().getId();
        Long salaId = fixture.sala().getId();
        Long gestorId = fixture.gestor().getId();

        service.eliminarSector(ADMIN_ID, hospital.getId(), sectorId);
        em.flush();
        em.clear();

        Sector sectorArchivado = sectores.findById(sectorId).orElseThrow();
        Sala salaArchivada = salas.findById(salaId).orElseThrow();
        assertTrue(sectorArchivado.isArchivado());
        assertTrue(!sectorArchivado.isActiva());
        assertTrue(!salaArchivada.isActiva());
        assertTrue(consultas.findById(consulta.getId()).isPresent());
        GestorDeCola gestorConHistorial = gestores.findById(gestorId).orElseThrow();
        assertTrue(gestorConHistorial.getConsultasEnEspera().stream()
                .anyMatch(item -> item.getId().equals(consulta.getId())));
        var configuracion = service.obtener(ADMIN_ID, hospital.getId());
        assertTrue(configuracion.sectores().stream().noneMatch(item -> item.id().equals(sectorId)));
        assertTrue(configuracion.salas().stream().noneMatch(item -> item.id().equals(salaId)));
        assertEquals(2, auditorias.findAll().size());
    }

    @Test
    void sesionFinalizadaArchivaSectorYSalaYConservaHistorial() {
        Fixture fixture = guardarFixture(hospital, false);
        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setHospital(hospital);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(fixture.sector());
        sesion.setSala(fixture.sala());
        sesion.setEstado(EstadoSesionMedica.FINALIZADA);
        em.persist(sesion);
        em.flush();

        service.eliminarSector(ADMIN_ID, hospital.getId(), fixture.sector().getId());
        em.flush();
        em.clear();

        assertTrue(sectores.findById(fixture.sector().getId()).orElseThrow().isArchivado());
        assertTrue(!sectores.findById(fixture.sector().getId()).orElseThrow().isActiva());
        assertTrue(!salas.findById(fixture.sala().getId()).orElseThrow().isActiva());
        assertTrue(sesiones.findById(sesion.getId()).isPresent());
        var configuracion = service.obtener(ADMIN_ID, hospital.getId());
        assertTrue(configuracion.sectores().stream().noneMatch(item -> item.id().equals(fixture.sector().getId())));
        assertTrue(configuracion.salas().stream().noneMatch(item -> item.id().equals(fixture.sala().getId())));
        assertEquals(2, auditorias.findAll().size());
    }

    @Test
    void cerrarSesionActivaPorAdminPermiteEliminarYArchivarSectorSinPaciente() {
        Fixture fixture = guardarFixture(hospital, false);
        UsuarioAuth medicoAuth = new UsuarioAuth();
        medicoAuth.setId("auth0|sector-delete-doctor-test");
        medicoAuth.setNombre("Médico");
        medicoAuth.setApellido("Test");
        em.persist(medicoAuth);
        Medico medico = new Medico();
        medico.setUsuarioAuth(medicoAuth);
        em.persist(medico);

        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setMedico(medico);
        sesion.setHospital(hospital);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(fixture.sector());
        sesion.setSala(fixture.sala());
        sesion.setEstado(EstadoSesionMedica.ACTIVA);
        em.persist(sesion);
        em.flush();
        Long sectorId = fixture.sector().getId();
        Long salaId = fixture.sala().getId();
        Long sessionId = sesion.getId();

        sesionesAdmin.cerrar(ADMIN_ID, hospital.getId(), sectorId, sessionId);
        service.eliminarSector(ADMIN_ID, hospital.getId(), sectorId);
        em.flush();
        em.clear();

        assertEquals(EstadoSesionMedica.FINALIZADA, sesiones.findById(sessionId).orElseThrow().getEstado());
        assertTrue(sectores.findById(sectorId).orElseThrow().isArchivado());
        assertTrue(!sectores.findById(sectorId).orElseThrow().isActiva());
        assertTrue(!salas.findById(salaId).orElseThrow().isActiva());
        assertTrue(sesiones.findById(sessionId).isPresent());
        var configuracion = service.obtener(ADMIN_ID, hospital.getId());
        assertTrue(configuracion.sectores().stream().noneMatch(item -> item.id().equals(sectorId)));
        assertTrue(configuracion.salas().stream().noneMatch(item -> item.id().equals(salaId)));
    }

    @Test
    void configuracionListaSoloSalasConSesionActivaOPausadaDelHospitalSolicitado() {
        Fixture a = guardarFixture(hospital, false);
        Fixture aPausada = guardarFixture(hospital, false, "Sector A pausado", "Sala A pausada");
        Fixture aFinalizada = guardarFixture(hospital, false, "Sector A finalizado", "Sala A finalizada");
        Fixture b = guardarFixture(otroHospital, false, "Sector B", "Sala B");
        guardarSesion(hospital, a, EstadoSesionMedica.ACTIVA);
        guardarSesion(hospital, aPausada, EstadoSesionMedica.PAUSADA);
        guardarSesion(hospital, aFinalizada, EstadoSesionMedica.FINALIZADA);
        guardarSesion(otroHospital, b, EstadoSesionMedica.ACTIVA);
        em.flush();
        em.clear();

        var response = service.obtener(ADMIN_ID, hospital.getId());

        assertEquals(java.util.Set.of(a.sala().getId(), aPausada.sala().getId()),
                java.util.Set.copyOf(response.salasOcupadasIds()));
    }

    private Hospital guardarHospital(String placeId) {
        Hospital result = new Hospital();
        result.setPlaceId(placeId);
        result.setNombre(placeId);
        result.getEspecialidades().add(especialidad);
        return em.persist(result);
    }

    private Fixture guardarFixture(Hospital propietario, boolean crearGestor) {
        return guardarFixture(propietario, crearGestor, "Sector", "Sala");
    }

    private Fixture guardarFixture(Hospital propietario, boolean crearGestor, String sectorNombre, String salaNombre) {
        Sector sector = new Sector();
        sector.setNombre(sectorNombre);
        sector.setHospital(propietario);
        sector.setEspecialidad(especialidad);
        em.persist(sector);

        Sala sala = new Sala();
        sala.setNombre(salaNombre);
        sala.setHospital(propietario);
        sala.setSector(sector);
        sala.setEspecialidad(especialidad);
        em.persist(sala);

        GestorDeCola gestor = null;
        if (crearGestor) {
            gestor = new GestorDeCola();
            gestor.setHospital(propietario);
            gestor.setEspecialidad(especialidad);
            gestor.setSector(sector);
            em.persist(gestor);
        }
        return new Fixture(sector, sala, gestor);
    }

    private void guardarSesion(Hospital propietario, Fixture fixture, EstadoSesionMedica estado) {
        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setHospital(propietario);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(fixture.sector());
        sesion.setSala(fixture.sala());
        sesion.setEstado(estado);
        em.persist(sesion);
    }

    private record Fixture(Sector sector, Sala sala, GestorDeCola gestor) { }
}
