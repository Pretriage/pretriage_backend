package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.metricas.DistribucionNivelItem;
import com.pretriage.backend.controllers.dtos.metricas.MetricasHospitalResponse;
import com.pretriage.backend.controllers.dtos.metricas.SerieDiariaItem;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.repositories.RepoAtencionesMedicas;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.projections.AtencionEmbudoProjection;
import com.pretriage.backend.repositories.projections.ConteoPorNivelProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricasHospitalServiceTest {

    private static final String ADMIN = "auth0|admin";
    private static final Long HOSPITAL = 1L;
    private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 9, 2);
    private static final LocalDateTime INICIO = DESDE.atStartOfDay();
    private static final LocalDateTime FIN = HASTA.plusDays(1).atStartOfDay();

    @Mock
    private StaffAccessService staffAccessService;
    @Mock
    private RepoEntradasCola repoEntradasCola;
    @Mock
    private RepoAtencionesMedicas repoAtencionesMedicas;
    @Mock
    private RepoConsultasMedicas repoConsultasMedicas;

    @InjectMocks
    private MetricasHospitalService service;

    /**
     * Escenario del contrato canónico: 60 ingresos el 01/09, 42 atendidos con espera de 37.5 min,
     * 45 con pretriage del chatbot, ninguno el 02/09.
     */
    private void stubContratoCanonico() {
        stubContratoCanonico(List.of(
                new ConteoPorNivelProjection(NivelDeGravedad.NORMAL, 22L),
                new ConteoPorNivelProjection(NivelDeGravedad.RIESGO_VITAL_INMEDIATO, 3L),
                new ConteoPorNivelProjection(NivelDeGravedad.URGENTE, 20L),
                new ConteoPorNivelProjection(NivelDeGravedad.MUY_URGENTE, 7L),
                new ConteoPorNivelProjection(NivelDeGravedad.NO_URGENTE, 8L)));
    }

    private void stubContratoCanonico(List<ConteoPorNivelProjection> conteoBot) {
        LocalDateTime ingreso = LocalDateTime.of(2026, 9, 1, 10, 0);
        List<LocalDateTime> ingresos = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ingresos.add(ingreso.plusMinutes(i));
        }
        List<AtencionEmbudoProjection> atendidos = new ArrayList<>();
        for (int i = 0; i < 42; i++) {
            // Mitad espera 30 min, mitad 45 min → promedio 37.5
            long espera = i % 2 == 0 ? 30 : 45;
            LocalDateTime ing = ingreso.plusMinutes(i);
            atendidos.add(new AtencionEmbudoProjection(ing, ing.plusMinutes(espera), ing.plusMinutes(espera + 15)));
        }
        when(repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(HOSPITAL, INICIO, FIN)).thenReturn(ingresos);
        when(repoAtencionesMedicas.findAtencionesDelEmbudo(HOSPITAL, INICIO, FIN, EstadoAtencionMedica.FINALIZADA))
                .thenReturn(atendidos);
        when(repoConsultasMedicas.contarIngresadosPorNivelBot(HOSPITAL, INICIO, FIN)).thenReturn(conteoBot);
        when(repoConsultasMedicas.contarIngresadosPorNivelMedico(HOSPITAL, INICIO, FIN)).thenReturn(List.of(
                new ConteoPorNivelProjection(null, 10L),
                new ConteoPorNivelProjection(NivelDeGravedad.RIESGO_VITAL_INMEDIATO, 2L),
                new ConteoPorNivelProjection(NivelDeGravedad.MUY_URGENTE, 4L),
                new ConteoPorNivelProjection(NivelDeGravedad.URGENTE, 15L),
                new ConteoPorNivelProjection(NivelDeGravedad.NORMAL, 17L),
                new ConteoPorNivelProjection(NivelDeGravedad.NO_URGENTE, 12L)));
        when(repoConsultasMedicas.contarIngresadosConPretriage(HOSPITAL, INICIO, FIN)).thenReturn(45L);
        when(repoConsultasMedicas.contarIngresadosSinPretriage(HOSPITAL, INICIO, FIN)).thenReturn(15L);
    }

    @Test
    void calculaElEmbudoConEsperaPromedioYPorcentajes() {
        stubContratoCanonico();

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, DESDE, HASTA);

        verify(staffAccessService).exigirAdminHospital(ADMIN, HOSPITAL);
        assertEquals(HOSPITAL, r.hospitalId());
        assertEquals(DESDE, r.desde());
        assertEquals(HASTA, r.hasta());
        assertEquals(60, r.ingresaronACola());
        assertEquals(42, r.pacientesAtendidos());
        assertEquals(18, r.pacientesNoAtendidos());
        assertEquals(37.5, r.esperaPromedioMinutos());
        assertEquals(70.0, r.porcentajeAtendidos());
        assertEquals(45, r.pretriageRealizado());
        assertEquals(15, r.pretriageNoRealizado());
        assertEquals(75.0, r.porcentajePretriageRealizado());
    }

    @Test
    void distribucionesTienenOrdenFijoTodosLosBucketsYCierranConLosIngresos() {
        stubContratoCanonico();

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, DESDE, HASTA);

        List<String> ordenBot = List.of("RIESGO_VITAL_INMEDIATO", "MUY_URGENTE", "URGENTE", "NORMAL", "NO_URGENTE");
        assertEquals(ordenBot, r.distribucionGravedadBot().stream().map(DistribucionNivelItem::nivel).toList());
        List<String> ordenMedico = new ArrayList<>(ordenBot);
        ordenMedico.add("SIN_REVISION");
        assertEquals(ordenMedico, r.distribucionGravedadMedico().stream().map(DistribucionNivelItem::nivel).toList());

        assertEquals(List.of(3L, 7L, 20L, 22L, 8L),
                r.distribucionGravedadBot().stream().map(DistribucionNivelItem::cantidad).toList());
        assertEquals(List.of(5.0, 11.7, 33.3, 36.7, 13.3),
                r.distribucionGravedadBot().stream().map(DistribucionNivelItem::porcentaje).toList());
        assertEquals(List.of(2L, 4L, 15L, 17L, 12L, 10L),
                r.distribucionGravedadMedico().stream().map(DistribucionNivelItem::cantidad).toList());
        assertEquals(16.7, r.distribucionGravedadMedico().getLast().porcentaje());

        assertEquals(r.ingresaronACola(), r.distribucionGravedadBot().stream().mapToLong(DistribucionNivelItem::cantidad).sum());
        assertEquals(r.ingresaronACola(), r.distribucionGravedadMedico().stream().mapToLong(DistribucionNivelItem::cantidad).sum());
        assertEquals(r.ingresaronACola(), r.pretriageRealizado() + r.pretriageNoRealizado());
        assertEquals(r.ingresaronACola() - r.pacientesAtendidos(), r.pacientesNoAtendidos());
    }

    @Test
    void bucketsSinDatosVienenConCantidadCero() {
        stubContratoCanonico(List.of(new ConteoPorNivelProjection(NivelDeGravedad.NORMAL, 60L)));

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, DESDE, HASTA);

        assertEquals(5, r.distribucionGravedadBot().size());
        assertEquals(0L, r.distribucionGravedadBot().getFirst().cantidad());
        assertEquals(0.0, r.distribucionGravedadBot().getFirst().porcentaje());
        assertEquals(100.0, r.distribucionGravedadBot().get(3).porcentaje());
    }

    @Test
    void serieDiariaEsContinuaYCierraConLosTotales() {
        stubContratoCanonico();

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, DESDE, HASTA);

        List<SerieDiariaItem> serie = r.serieDiaria();
        assertEquals(2, serie.size());
        assertEquals(DESDE, serie.get(0).fecha());
        assertEquals(60, serie.get(0).ingresados());
        assertEquals(42, serie.get(0).atendidos());
        assertEquals(37.5, serie.get(0).esperaPromedioMinutos());
        assertEquals(HASTA, serie.get(1).fecha());
        assertEquals(0, serie.get(1).ingresados());
        assertEquals(0, serie.get(1).atendidos());
        assertNull(serie.get(1).esperaPromedioMinutos());
        assertEquals(r.ingresaronACola(), serie.stream().mapToLong(SerieDiariaItem::ingresados).sum());
        assertEquals(r.pacientesAtendidos(), serie.stream().mapToLong(SerieDiariaItem::atendidos).sum());
    }

    @Test
    void serieDiariaAgrupaPorDiaDeIngresoYCalculaEsperaPorDia() {
        LocalDate desde = LocalDate.of(2026, 9, 1);
        LocalDate hasta = LocalDate.of(2026, 9, 5);
        LocalDateTime inicio = desde.atStartOfDay();
        LocalDateTime fin = hasta.plusDays(1).atStartOfDay();
        LocalDateTime dia1 = LocalDateTime.of(2026, 9, 1, 23, 50);
        LocalDateTime dia3 = LocalDateTime.of(2026, 9, 3, 9, 0);
        when(repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(HOSPITAL, inicio, fin))
                .thenReturn(List.of(dia1, dia3, dia3.plusMinutes(5)));
        // Ingresó el 01/09 y lo atendieron el 02/09: cuenta como atendido del 01/09 (ancla en el ingreso).
        when(repoAtencionesMedicas.findAtencionesDelEmbudo(HOSPITAL, inicio, fin, EstadoAtencionMedica.FINALIZADA))
                .thenReturn(List.of(
                        new AtencionEmbudoProjection(dia1, dia1.plusMinutes(20), dia1.plusMinutes(40)),
                        new AtencionEmbudoProjection(dia3, dia3.plusMinutes(10), dia3.plusMinutes(30))));
        when(repoConsultasMedicas.contarIngresadosPorNivelBot(HOSPITAL, inicio, fin)).thenReturn(List.of());
        when(repoConsultasMedicas.contarIngresadosPorNivelMedico(HOSPITAL, inicio, fin)).thenReturn(List.of());

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, desde, hasta);

        List<SerieDiariaItem> serie = r.serieDiaria();
        assertEquals(5, serie.size());
        assertEquals(List.of(1L, 0L, 2L, 0L, 0L), serie.stream().map(SerieDiariaItem::ingresados).toList());
        assertEquals(List.of(1L, 0L, 1L, 0L, 0L), serie.stream().map(SerieDiariaItem::atendidos).toList());
        assertEquals(20.0, serie.get(0).esperaPromedioMinutos());
        assertNull(serie.get(1).esperaPromedioMinutos());
        assertEquals(10.0, serie.get(2).esperaPromedioMinutos());
        assertEquals(15.0, r.esperaPromedioMinutos());
        assertEquals(66.7, r.porcentajeAtendidos());
    }

    @Test
    void sinIngresosDevuelveConteosEnCeroYNullsDondeElDenominadorEsCero() {
        when(repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(HOSPITAL, INICIO, FIN)).thenReturn(List.of());
        when(repoAtencionesMedicas.findAtencionesDelEmbudo(HOSPITAL, INICIO, FIN, EstadoAtencionMedica.FINALIZADA))
                .thenReturn(List.of());
        when(repoConsultasMedicas.contarIngresadosPorNivelBot(HOSPITAL, INICIO, FIN)).thenReturn(List.of());
        when(repoConsultasMedicas.contarIngresadosPorNivelMedico(HOSPITAL, INICIO, FIN)).thenReturn(List.of());

        MetricasHospitalResponse r = service.obtenerMetricas(ADMIN, HOSPITAL, DESDE, HASTA);

        assertEquals(0, r.ingresaronACola());
        assertEquals(0, r.pacientesAtendidos());
        assertEquals(0, r.pacientesNoAtendidos());
        assertEquals(0, r.pretriageRealizado());
        assertEquals(0, r.pretriageNoRealizado());
        assertNull(r.esperaPromedioMinutos());
        assertNull(r.porcentajeAtendidos());
        assertNull(r.porcentajePretriageRealizado());
        assertEquals(5, r.distribucionGravedadBot().size());
        assertEquals(6, r.distribucionGravedadMedico().size());
        assertTrue(r.distribucionGravedadBot().stream().allMatch(i -> i.cantidad() == 0 && i.porcentaje() == null));
        assertTrue(r.distribucionGravedadMedico().stream().allMatch(i -> i.cantidad() == 0 && i.porcentaje() == null));
        assertEquals(2, r.serieDiaria().size());
        assertTrue(r.serieDiaria().stream().allMatch(
                d -> d.ingresados() == 0 && d.atendidos() == 0 && d.esperaPromedioMinutos() == null));
    }

    @Test
    void rangoInvertidoLanzaIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.obtenerMetricas(ADMIN, HOSPITAL, HASTA, DESDE));
        verifyNoInteractions(repoEntradasCola, repoAtencionesMedicas, repoConsultasMedicas);
    }

    @Test
    void hospitalAjenoLanzaAccessDeniedSinConsultarDatos() {
        when(staffAccessService.exigirAdminHospital("auth0|otro", HOSPITAL))
                .thenThrow(new AccessDeniedException("No administrás este hospital"));

        assertThrows(AccessDeniedException.class,
                () -> service.obtenerMetricas("auth0|otro", HOSPITAL, DESDE, HASTA));
        verify(repoEntradasCola, never()).findFechasHoraIngresoByHospitalEnRango(anyLong(), any(), any());
        verifyNoInteractions(repoAtencionesMedicas, repoConsultasMedicas);
    }
}
