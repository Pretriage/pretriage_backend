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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Métricas del hospital para su administrador. Todas las métricas se anclan en el ingreso a la cola
 * ({@code EntradaCola.fechaHoraIngreso}) dentro de {@code [desde 00:00, hasta+1 00:00)}: "atendidos" son,
 * entre esos ingresos, los que tienen la atención médica FINALIZADA.
 */
@Service
@RequiredArgsConstructor
public class MetricasHospitalService {

    static final String SIN_REVISION = "SIN_REVISION";

    private final StaffAccessService staffAccessService;
    private final RepoEntradasCola repoEntradasCola;
    private final RepoAtencionesMedicas repoAtencionesMedicas;
    private final RepoConsultasMedicas repoConsultasMedicas;

    @Transactional
    public MetricasHospitalResponse obtenerMetricas(String subject, Long hospitalId, LocalDate desde, LocalDate hasta) {
        staffAccessService.exigirAdminHospital(subject, hospitalId);
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException("Se deben indicar las fechas desde y hasta");
        }
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        if (desde.isEqual(hasta)) {
            throw new IllegalArgumentException("La fecha desde no puede ser igual a la fecha hasta");
        }

        LocalDateTime inicio = desde.atStartOfDay();
        LocalDateTime fin = hasta.plusDays(1).atStartOfDay();

        List<LocalDateTime> ingresos = repoEntradasCola.findFechasHoraIngresoByHospitalEnRango(hospitalId, inicio, fin);
        List<AtencionEmbudoProjection> atendidos = repoAtencionesMedicas.findAtencionesDelEmbudo(
                hospitalId, inicio, fin, EstadoAtencionMedica.FINALIZADA);

        long ingresaronACola = ingresos.size();
        long pacientesAtendidos = atendidos.size();

        Map<NivelDeGravedad, Long> conteoBot = aMapa(
                repoConsultasMedicas.contarIngresadosPorNivelBot(hospitalId, inicio, fin));
        Map<NivelDeGravedad, Long> conteoMedico = aMapa(
                repoConsultasMedicas.contarIngresadosPorNivelMedico(hospitalId, inicio, fin));
        long pretriageRealizado = repoConsultasMedicas.contarIngresadosConPretriage(hospitalId, inicio, fin);
        long pretriageNoRealizado = repoConsultasMedicas.contarIngresadosSinPretriage(hospitalId, inicio, fin);

        return new MetricasHospitalResponse(
                hospitalId,
                desde,
                hasta,
                ingresaronACola,
                pacientesAtendidos,
                ingresaronACola - pacientesAtendidos,
                esperaPromedioMinutos(atendidos),
                porcentaje(pacientesAtendidos, ingresaronACola),
                distribucion(conteoBot, ingresaronACola, false),
                distribucion(conteoMedico, ingresaronACola, true),
                pretriageRealizado,
                pretriageNoRealizado,
                porcentaje(pretriageRealizado, ingresaronACola),
                serieDiaria(desde, hasta, ingresos, atendidos));
    }

    private static Map<NivelDeGravedad, Long> aMapa(List<ConteoPorNivelProjection> conteos) {
        Map<NivelDeGravedad, Long> mapa = new HashMap<>();
        conteos.forEach(c -> mapa.merge(c.nivel(), c.cantidad() == null ? 0L : c.cantidad(), Long::sum));
        return mapa;
    }

    /**
     * Arma la distribución completa y en orden de declaración de {@link NivelDeGravedad}. En la versión
     * del médico agrega {@code SIN_REVISION} al final con las consultas sin nivel médico (clave null).
     */
    private static List<DistribucionNivelItem> distribucion(Map<NivelDeGravedad, Long> conteos, long total,
            boolean incluirSinRevision) {
        List<DistribucionNivelItem> items = new ArrayList<>();
        for (NivelDeGravedad nivel : NivelDeGravedad.values()) {
            long cantidad = conteos.getOrDefault(nivel, 0L);
            items.add(new DistribucionNivelItem(nivel.name(), cantidad, porcentaje(cantidad, total)));
        }
        if (incluirSinRevision) {
            long sinRevision = conteos.getOrDefault(null, 0L);
            items.add(new DistribucionNivelItem(SIN_REVISION, sinRevision, porcentaje(sinRevision, total)));
        }
        return items;
    }

    private static List<SerieDiariaItem> serieDiaria(LocalDate desde, LocalDate hasta, List<LocalDateTime> ingresos,
            List<AtencionEmbudoProjection> atendidos) {
        Map<LocalDate, Long> ingresosPorDia = ingresos.stream()
                .collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));
        Map<LocalDate, List<AtencionEmbudoProjection>> atendidosPorDia = atendidos.stream()
                .collect(Collectors.groupingBy(a -> a.fechaHoraIngreso().toLocalDate()));

        List<SerieDiariaItem> serie = new ArrayList<>();
        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            List<AtencionEmbudoProjection> atendidosDelDia = atendidosPorDia.getOrDefault(dia, List.of());
            serie.add(new SerieDiariaItem(
                    dia,
                    ingresosPorDia.getOrDefault(dia, 0L),
                    atendidosDelDia.size(),
                    esperaPromedioMinutos(atendidosDelDia)));
        }
        return serie;
    }

    /** Promedio de (inicio de la atención - ingreso a la cola) en minutos, con un decimal; null si no hay datos. */
    private static Double esperaPromedioMinutos(List<AtencionEmbudoProjection> atendidos) {
        List<Long> esperasEnSegundos = atendidos.stream()
                .filter(a -> a.fechaHoraIngreso() != null && a.fechaHoraInicio() != null)
                .map(a -> Duration.between(a.fechaHoraIngreso(), a.fechaHoraInicio()).toSeconds())
                .toList();
        if (esperasEnSegundos.isEmpty()) {
            return null;
        }
        double promedioSegundos = esperasEnSegundos.stream().mapToLong(Long::longValue).average().orElse(0);
        return redondear(promedioSegundos / 60.0);
    }

    private static Double porcentaje(long parte, long total) {
        if (total == 0) {
            return null;
        }
        return redondear(parte * 100.0 / total);
    }

    private static Double redondear(double valor) {
        return BigDecimal.valueOf(valor).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
