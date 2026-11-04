package com.pretriage.backend.repositories;

import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.repositories.projections.AtencionEmbudoProjection;
import com.pretriage.backend.repositories.projections.ConteoPorNivelProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queries con date-range usadas por las métricas del hospital. Seed:
 * 60 ingresos del hospital A en septiembre 2026 (12 por nivel bot), 42 con atención FINALIZADA,
 * 1 con atención EN_CURSO, 40 con nivel médico (20 sin revisión) y 45 con chat vinculado;
 * además ingresos fuera del rango (31/08 23:59 y 01/10 00:00) y uno del hospital B en el rango.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoMetricasHospitalTest {

    private static final LocalDateTime DESDE = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime HASTA = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Autowired
    private RepoEntradasCola repoEntradasCola;

    @Autowired
    private RepoAtencionesMedicas repoAtencionesMedicas;

    @Autowired
    private RepoConsultasMedicas repoConsultasMedicas;

    @Autowired
    private TestEntityManager entityManager;

    private Hospital hospitalA;
    private Hospital hospitalB;
    private GestorDeCola gestorA;
    private GestorDeCola gestorB;
    private SesionAtencionMedica sesion;

    @BeforeEach
    void seed() {
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setCodigo("METRICAS_TEST");
        especialidad.setNombre("Metricas test");
        entityManager.persist(especialidad);

        hospitalA = persistirHospital("hospital-a");
        hospitalB = persistirHospital("hospital-b");
        gestorA = persistirGestor(hospitalA, especialidad);
        gestorB = persistirGestor(hospitalB, especialidad);

        Medico medico = new Medico();
        entityManager.persist(medico);
        sesion = new SesionAtencionMedica();
        sesion.setMedico(medico);
        sesion.setHospital(hospitalA);
        sesion.setEspecialidad(especialidad);
        entityManager.persist(sesion);

        NivelDeGravedad[] niveles = NivelDeGravedad.values();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
        for (int i = 0; i < 60; i++) {
            LocalDateTime ingreso = base.plusHours(i);
            NivelDeGravedad nivelMedico = i < 40 ? niveles[i % niveles.length] : null;
            EstadoAtencionMedica estadoAtencion = i < 42 ? EstadoAtencionMedica.FINALIZADA
                    : i == 42 ? EstadoAtencionMedica.EN_CURSO : null;
            persistirIngreso(gestorA, ingreso, niveles[i % niveles.length], nivelMedico, i < 45, estadoAtencion);
        }

        // Fuera del rango (bordes) y de otro hospital: no deben contarse.
        persistirIngreso(gestorA, DESDE.minusMinutes(1), NivelDeGravedad.URGENTE, NivelDeGravedad.URGENTE, true,
                EstadoAtencionMedica.FINALIZADA);
        persistirIngreso(gestorA, HASTA, NivelDeGravedad.URGENTE, NivelDeGravedad.URGENTE, true,
                EstadoAtencionMedica.FINALIZADA);
        persistirIngreso(gestorB, base, NivelDeGravedad.URGENTE, NivelDeGravedad.URGENTE, true,
                EstadoAtencionMedica.FINALIZADA);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void ingresosDelHospitalEnRangoDevuelveSoloLosDelPeriodo() {
        List<LocalDateTime> ingresos = repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(
                hospitalA.getId(), DESDE, HASTA);

        assertEquals(60, ingresos.size());
        assertTrue(ingresos.stream().allMatch(f -> !f.isBefore(DESDE) && f.isBefore(HASTA)));
    }

    @Test
    void ingresosFueraDelPeriodoDevuelveCero() {
        List<LocalDateTime> ingresos = repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(
                hospitalA.getId(), LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0));

        assertEquals(0, ingresos.size());
    }

    @Test
    void atencionesDelEmbudoDevuelveSoloFinalizadasDelHospitalYPeriodo() {
        List<AtencionEmbudoProjection> atenciones = repoAtencionesMedicas.findAtencionesDelEmbudo(
                hospitalA.getId(), DESDE, HASTA, EstadoAtencionMedica.FINALIZADA);

        assertEquals(42, atenciones.size());
        assertTrue(atenciones.stream().allMatch(a -> !a.fechaHoraIngreso().isBefore(DESDE)
                && a.fechaHoraIngreso().isBefore(HASTA)));
        atenciones.forEach(a -> {
            assertNotNull(a.fechaHoraInicio());
            assertNotNull(a.fechaHoraFin());
            assertEquals(30, java.time.Duration.between(a.fechaHoraIngreso(), a.fechaHoraInicio()).toMinutes());
        });
    }

    @Test
    void distribucionesYPretriageCierranConLosIngresos() {
        Map<NivelDeGravedad, Long> bot = aMapa(repoConsultasMedicas.contarIngresadosPorNivelBot(
                hospitalA.getId(), DESDE, HASTA));
        Map<NivelDeGravedad, Long> medico = aMapa(repoConsultasMedicas.contarIngresadosPorNivelMedico(
                hospitalA.getId(), DESDE, HASTA));
        long conPretriage = repoConsultasMedicas.contarIngresadosConPretriage(hospitalA.getId(), DESDE, HASTA);
        long sinPretriage = repoConsultasMedicas.contarIngresadosSinPretriage(hospitalA.getId(), DESDE, HASTA);

        assertEquals(60L, bot.values().stream().mapToLong(Long::longValue).sum());
        for (NivelDeGravedad nivel : NivelDeGravedad.values()) {
            assertEquals(12L, bot.get(nivel));
            assertEquals(8L, medico.get(nivel));
        }
        assertEquals(20L, medico.get(null));
        assertEquals(60L, medico.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(45L, conPretriage);
        assertEquals(15L, sinPretriage);
    }

    private Map<NivelDeGravedad, Long> aMapa(List<ConteoPorNivelProjection> conteos) {
        Map<NivelDeGravedad, Long> mapa = new HashMap<>();
        conteos.forEach(c -> mapa.put(c.nivel(), c.cantidad()));
        return mapa;
    }

    private Hospital persistirHospital(String placeId) {
        Hospital hospital = new Hospital();
        hospital.setPlaceId(placeId);
        hospital.setNombre(placeId);
        return entityManager.persist(hospital);
    }

    private GestorDeCola persistirGestor(Hospital hospital, EspecialidadMedica especialidad) {
        Sector sector = new Sector();
        sector.setNombre("sector " + hospital.getPlaceId());
        sector.setHospital(hospital);
        sector.setEspecialidad(especialidad);
        entityManager.persist(sector);

        GestorDeCola gestor = new GestorDeCola();
        gestor.setHospital(hospital);
        gestor.setEspecialidad(especialidad);
        gestor.setSector(sector);
        return entityManager.persist(gestor);
    }

    private void persistirIngreso(GestorDeCola gestor, LocalDateTime ingreso, NivelDeGravedad nivelBot,
            NivelDeGravedad nivelMedico, boolean conChat, EstadoAtencionMedica estadoAtencion) {
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setHospital(gestor.getHospital());
        consulta.setEspecialidad(gestor.getEspecialidad());
        consulta.setSector(gestor.getSector());
        consulta.setFechaHoraCreacion(ingreso);
        consulta.setNivelDeGravedadBot(nivelBot);
        consulta.setNivelDeGravedadMedico(nivelMedico);
        if (conChat) {
            Chat chat = new Chat();
            chat.setFinalizado(true);
            chat.setFechaHoraCreacion(ingreso.minusMinutes(10));
            entityManager.persist(chat);
            consulta.setChat(chat);
        }
        entityManager.persist(consulta);

        EntradaCola entrada = new EntradaCola();
        entrada.setGestorDeCola(gestor);
        entrada.setConsultaMedica(consulta);
        entrada.setFechaHoraIngreso(ingreso);
        entrada.setEstado(estadoAtencion == EstadoAtencionMedica.FINALIZADA ? EstadoEntradaCola.FINALIZADA
                : estadoAtencion == EstadoAtencionMedica.EN_CURSO ? EstadoEntradaCola.EN_ATENCION
                : EstadoEntradaCola.EN_COLA);
        entityManager.persist(entrada);

        if (estadoAtencion != null) {
            AtencionMedica atencion = new AtencionMedica();
            atencion.setConsultaMedica(consulta);
            atencion.setSesionAtencionMedica(sesion);
            atencion.setEstado(estadoAtencion);
            atencion.setFechaHoraInicio(ingreso.plusMinutes(30));
            if (estadoAtencion == EstadoAtencionMedica.FINALIZADA) {
                atencion.setFechaHoraFin(ingreso.plusMinutes(45));
            }
            entityManager.persist(atencion);
        }
    }
}
