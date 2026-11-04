package com.pretriage.backend.config;

import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos sintéticos opt-in para probar manualmente
 * {@code GET /api/admin/hospitales/{hospitalId}/metricas}.
 *
 * <p>Solo se activa con {@code pretriage.seed.metricas-demo.enabled=true}. Al activarlo,
 * también requiere {@code pretriage.seed.metricas-demo.hospital-place-id}, que debe apuntar
 * a un hospital de demostración desechable. Siembra 310 ingresos a cola entre el
 * 01/10/2026 y el 23/10/2026, con 269 atenciones finalizadas. No crea usuarios ni
 * membresías: la membresía {@code ADMIN_HOSPITAL} debe existir para consultar el endpoint.</p>
 *
 * <p>Si el hospital no existe o ya hay consultas con el prefijo {@value #PREFIJO_LLAMADO},
 * no inserta nada. La propiedad de activación no tiene valor predeterminado, por lo que
 * este seed no participa en el arranque normal de la aplicación.</p>
 */
@Slf4j
@Component
@Order(20)
@ConditionalOnProperty(name = "pretriage.seed.metricas-demo.enabled", havingValue = "true", matchIfMissing = false)
public class MetricasHospitalTestInyection implements ApplicationRunner {

    private static final String ESPECIALIDAD_CODIGO = "CLINICA_MEDICA";
    private static final String PREFIJO_LLAMADO = "MTR-";
    private static final String NOMBRE_SECTOR = "Sector metricas demo";
    private static final String NOMBRE_SALA = "Sala metricas demo";
    private static final String MATRICULA_MEDICO = "MTR-DEMO-001";

    private static final LocalDate DESDE = LocalDate.of(2026, 10, 1);

    /**
     * Plan diario del 01/10 al 23/10. Cada posición representa un día consecutivo.
     * Los días 04/10, 11/10 y 18/10 quedan sin actividad; el 22/10 tiene ingresos
     * pero ninguna atención finalizada.
     */
    private static final int[] INGRESOS = {
            13, 15, 11, 0, 17, 14, 19, 12, 16, 10, 0, 18, 15, 21, 30, 17, 11, 0, 20, 16, 14, 9, 12
    };

    private static final int[] ATENDIDOS = {
            12, 15, 10, 0, 15, 13, 17, 12, 15, 8, 0, 16, 14, 18, 26, 15, 10, 0, 17, 14, 14, 0, 8
    };

    /**
     * Espera base por día, en minutos. A cada consulta se le suma un desvío determinístico
     * de 0 a 24 minutos para que los promedios diarios no sean planos.
     */
    private static final int[] ESPERA_BASE = {
            10, 15, 20, 0, 25, 30, 35, 40, 45, 20, 0, 50, 55, 60, 80, 65, 45, 0, 70, 50, 40, 30, 20
    };

    private final JdbcTemplate jdbcTemplate;
    private final String hospitalPlaceId;

    public MetricasHospitalTestInyection(
            JdbcTemplate jdbcTemplate,
            @Value("${pretriage.seed.metricas-demo.hospital-place-id}") String hospitalPlaceId) {
        this.jdbcTemplate = jdbcTemplate;
        this.hospitalPlaceId = hospitalPlaceId;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long hospitalId = buscarHospital();
        if (hospitalId == null) {
            log.warn("Seed de métricas omitido: no existe el hospital de demostración con place_id '{}'.",
                    hospitalPlaceId);
            return;
        }

        Long especialidadId = buscarEspecialidad();
        if (yaSembrado(hospitalId)) {
            log.info("Seed de métricas omitido: ya existen consultas '{}' en el hospital {}.",
                    PREFIJO_LLAMADO + "%", hospitalId);
            return;
        }

        asegurarHospitalEspecialidad(hospitalId, especialidadId);
        Long sectorId = buscarOCrearSector(hospitalId, especialidadId);
        Long salaId = buscarOCrearSala(hospitalId, especialidadId, sectorId);
        Long medicoId = buscarOCrearMedico();
        Long gestorId = buscarOCrearGestor(hospitalId, especialidadId, sectorId);

        int totalIngresos = 0;
        int totalAtendidos = 0;
        int totalConPretriage = 0;
        int totalEnCurso = 0;
        int secuencia = 0;
        int atendidosGenerados = 0;
        int revisados = 0;

        for (int dia = 0; dia < INGRESOS.length; dia++) {
            if (INGRESOS[dia] == 0) {
                continue;
            }

            LocalDate fecha = DESDE.plusDays(dia);
            Long sesionId = crearSesion(fecha, hospitalId, especialidadId, sectorId, salaId, medicoId);

            for (int posicionDelDia = 0; posicionDelDia < INGRESOS[dia]; posicionDelDia++) {
                secuencia++;
                totalIngresos++;

                boolean atendido = posicionDelDia < ATENDIDOS[dia];
                boolean conPretriage = (secuencia - 1) % 31 < 20;
                NivelDeGravedad nivelBot = nivelBot(secuencia - 1);
                EstadoEntradaCola estadoEntrada = atendido
                        ? EstadoEntradaCola.FINALIZADA
                        : estadoNoAtendido(secuencia - 1);
                EstadoConsulta estadoConsulta = atendido
                        ? EstadoConsulta.FINALIZADA
                        : EstadoConsulta.valueOf(estadoEntrada.name());

                NivelDeGravedad nivelMedico = null;
                if (atendido) {
                    totalAtendidos++;
                    atendidosGenerados++;
                    // 4 de cada 5 atendidos tienen revisión médica; el resto queda SIN_REVISION.
                    if (atendidosGenerados % 5 != 0) {
                        nivelMedico = nivelMedico(revisados++);
                    }
                }

                LocalDateTime ingreso = fecha.atTime(8, 0)
                        .plusMinutes((secuencia * 17L) % 600);
                long esperaMinutos = ESPERA_BASE[dia] + ((secuencia * 7L) % 25);
                LocalDateTime inicioAtencion = ingreso.plusMinutes(esperaMinutos);
                LocalDateTime finAtencion = inicioAtencion.plusMinutes(10 + (secuencia % 21));

                String resultadoTriageJson = "{\"nivel\":\"" + nivelBot.name() + "\"}";
                Long pacienteId = crearPaciente(secuencia, conPretriage);
                Long chatId = conPretriage
                        ? crearChat(pacienteId, ingreso, resultadoTriageJson)
                        : null;

                boolean conAtencion = atendido || estadoEntrada == EstadoEntradaCola.EN_ATENCION;
                Long medicoConsulta = conAtencion ? medicoId : null;
                Long salaConsulta = conAtencion ? salaId : null;

                Long consultaId = crearConsulta(
                        secuencia,
                        hospitalId,
                        especialidadId,
                        sectorId,
                        pacienteId,
                        chatId,
                        nivelBot,
                        nivelMedico,
                        conPretriage ? resultadoTriageJson : null,
                        estadoConsulta,
                        medicoConsulta,
                        salaConsulta,
                        ingreso.minusMinutes(10));

                crearEntrada(
                        gestorId,
                        consultaId,
                        estadoEntrada,
                        tipoPausa(estadoEntrada),
                        prioridad(nivelBot),
                        secuencia,
                        ingreso,
                        fechaHoraLlamado(estadoEntrada, atendido, inicioAtencion, ingreso));

                if (atendido) {
                    crearAtencion(consultaId, sesionId, "FINALIZADA", inicioAtencion, finAtencion);
                } else if (estadoEntrada == EstadoEntradaCola.EN_ATENCION) {
                    totalEnCurso++;
                    crearAtencion(consultaId, sesionId, "EN_CURSO", inicioAtencion, null);
                }

                if (conPretriage) {
                    totalConPretriage++;
                }
            }
        }

        log.info("Seed de métricas listo: hospitalId={}, rango={}..{}, ingresos={}, atendidos={}, "
                        + "noAtendidos={}, conPretriage={}, sinPretriage={}, atencionesEnCurso={}.",
                hospitalId, DESDE, DESDE.plusDays(INGRESOS.length - 1L), totalIngresos, totalAtendidos,
                totalIngresos - totalAtendidos, totalConPretriage, totalIngresos - totalConPretriage,
                totalEnCurso);
        log.info("GET /api/admin/hospitales/{}/metricas?desde={}&hasta={}",
                hospitalId, DESDE, DESDE.plusDays(INGRESOS.length - 1L));
    }

    private Long buscarHospital() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from hospital where place_id = ? order by id limit 1",
                Long.class, hospitalPlaceId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long buscarEspecialidad() {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from especialidad_medica where codigo = ?",
                Long.class, ESPECIALIDAD_CODIGO);
        if (ids.isEmpty()) {
            throw new IllegalStateException(
                    "No existe la especialidad " + ESPECIALIDAD_CODIGO + "; verificá EspecialidadesMedicasSeeder");
        }
        return ids.getFirst();
    }

    private boolean yaSembrado(Long hospitalId) {
        Long cantidad = jdbcTemplate.queryForObject(
                "select count(*) from consulta_medica where id_hospital = ? and codigo_llamado like ?",
                Long.class, hospitalId, PREFIJO_LLAMADO + "%");
        return cantidad != null && cantidad > 0;
    }

    private void asegurarHospitalEspecialidad(Long hospitalId, Long especialidadId) {
        Long cantidad = jdbcTemplate.queryForObject(
                "select count(*) from hospital_especialidad_medica "
                        + "where id_hospital = ? and id_especialidad_medica = ?",
                Long.class, hospitalId, especialidadId);
        if (cantidad == null || cantidad == 0) {
            jdbcTemplate.update(
                    "insert into hospital_especialidad_medica (id_hospital, id_especialidad_medica) values (?, ?)",
                    hospitalId, especialidadId);
        }
    }

    private Long buscarOCrearSector(Long hospitalId, Long especialidadId) {
        Long existente = primerId(
                "select id from sector where id_hospital = ? and id_especialidad = ? and nombre = ? "
                        + "order by id limit 1",
                hospitalId, especialidadId, NOMBRE_SECTOR);
        if (existente != null) {
            return existente;
        }
        return jdbcTemplate.queryForObject(
                "insert into sector (nombre, activa, id_hospital, id_especialidad) "
                        + "values (?, true, ?, ?) returning id",
                Long.class, NOMBRE_SECTOR, hospitalId, especialidadId);
    }

    private Long buscarOCrearSala(Long hospitalId, Long especialidadId, Long sectorId) {
        Long existente = primerId(
                "select id from sala where id_hospital = ? and id_especialidad_medica = ? "
                        + "and id_sector = ? and nombre = ? order by id limit 1",
                hospitalId, especialidadId, sectorId, NOMBRE_SALA);
        if (existente != null) {
            return existente;
        }
        return jdbcTemplate.queryForObject(
                "insert into sala (nombre, id_sector, activa, id_hospital, id_especialidad_medica) "
                        + "values (?, ?, true, ?, ?) returning id",
                Long.class, NOMBRE_SALA, sectorId, hospitalId, especialidadId);
    }

    private Long buscarOCrearMedico() {
        Long existente = primerId(
                "select id from medico where matricula = ? order by id limit 1",
                MATRICULA_MEDICO);
        if (existente != null) {
            return existente;
        }
        return jdbcTemplate.queryForObject(
                "insert into medico (matricula) values (?) returning id",
                Long.class, MATRICULA_MEDICO);
    }

    private Long buscarOCrearGestor(Long hospitalId, Long especialidadId, Long sectorId) {
        Long existente = primerId(
                "select id from gestor_de_cola where id_hospital = ? and id_especialidad_medica = ? "
                        + "and id_sector = ? order by id limit 1",
                hospitalId, especialidadId, sectorId);
        if (existente != null) {
            return existente;
        }
        return jdbcTemplate.queryForObject(
                "insert into gestor_de_cola (id_hospital, id_especialidad_medica, id_sector) "
                        + "values (?, ?, ?) returning id",
                Long.class, hospitalId, especialidadId, sectorId);
    }

    private Long crearSesion(LocalDate fecha, Long hospitalId, Long especialidadId, Long sectorId,
            Long salaId, Long medicoId) {
        return jdbcTemplate.queryForObject(
                """
                        insert into sesion_atencion_medica
                        (id_medico, id_hospital, id_especialidad_medica, id_sala, id_sector,
                         estado, fecha_hora_inicio, fecha_hora_fin)
                        values (?, ?, ?, ?, ?, 'FINALIZADA', ?, ?)
                        returning id
                        """,
                Long.class, medicoId, hospitalId, especialidadId, salaId, sectorId,
                Timestamp.valueOf(fecha.atTime(7, 0)), Timestamp.valueOf(fecha.atTime(23, 0)));
    }

    private Long crearPaciente(int secuencia, boolean conPretriage) {
        return jdbcTemplate.queryForObject(
                """
                        insert into paciente
                        (nombre, apellido, numero_documento, tipo_documento, origen_registro)
                        values (?, ?, ?, 'DNI', ?)
                        returning id
                        """,
                Long.class,
                "Paciente",
                "Metricas " + secuencia,
                String.valueOf(99_000_000 + secuencia),
                conPretriage ? "APLICACION" : "RECEPCION");
    }

    private Long crearChat(Long pacienteId, LocalDateTime ingreso, String resultadoTriageJson) {
        return jdbcTemplate.queryForObject(
                """
                        insert into chat
                        (fecha_hora_creacion, paciente_id, finalizado, resultado_triage_json)
                        values (?, ?, true, ?)
                        returning id
                        """,
                Long.class,
                Timestamp.valueOf(ingreso.minusMinutes(15)),
                pacienteId,
                resultadoTriageJson);
    }

    private Long crearConsulta(int secuencia, Long hospitalId, Long especialidadId, Long sectorId,
            Long pacienteId, Long chatId, NivelDeGravedad nivelBot, NivelDeGravedad nivelMedico,
            String resumenPretriageJson, EstadoConsulta estadoConsulta, Long medicoId, Long salaId,
            LocalDateTime fechaHoraCreacion) {
        return jdbcTemplate.queryForObject(
                """
                        insert into consulta_medica
                        (fecha_hora_creacion, codigo_llamado, id_hospital, id_especialidad_medica,
                         id_medico, id_sala, id_sector, id_paciente, nivel_de_gravedad_bot,
                         nivel_de_gravedad_medico, resumen_pretriage_json, estado_consulta, id_chat)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """,
                Long.class,
                Timestamp.valueOf(fechaHoraCreacion),
                PREFIJO_LLAMADO + String.format("%04d", secuencia),
                hospitalId,
                especialidadId,
                medicoId,
                salaId,
                sectorId,
                pacienteId,
                nivelBot.name(),
                nivelMedico != null ? nivelMedico.name() : null,
                resumenPretriageJson,
                estadoConsulta.name(),
                chatId);
    }

    private void crearEntrada(Long gestorId, Long consultaId, EstadoEntradaCola estado,
            TipoPausaCola tipoPausa, int prioridad, long ordenRelativo, LocalDateTime ingreso,
            LocalDateTime fechaHoraLlamado) {
        jdbcTemplate.update(
                """
                        insert into entrada_cola
                        (id_gestor_de_cola, id_consulta_medica, estado, tipo_pausa, prioridad,
                         orden_relativo, fecha_hora_ingreso, fecha_hora_llamado)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                gestorId,
                consultaId,
                estado.name(),
                tipoPausa != null ? tipoPausa.name() : null,
                prioridad,
                ordenRelativo,
                Timestamp.valueOf(ingreso),
                fechaHoraLlamado != null ? Timestamp.valueOf(fechaHoraLlamado) : null);
    }

    private void crearAtencion(Long consultaId, Long sesionId, String estado,
            LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin) {
        jdbcTemplate.update(
                """
                        insert into atencion_medica
                        (id_consulta_medica, id_sesion_atencion_medica, estado,
                         fecha_hora_inicio, fecha_hora_fin)
                        values (?, ?, ?, ?, ?)
                        """,
                consultaId,
                sesionId,
                estado,
                Timestamp.valueOf(fechaHoraInicio),
                fechaHoraFin != null ? Timestamp.valueOf(fechaHoraFin) : null);
    }

    private Long primerId(String sql, Object... args) {
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static NivelDeGravedad nivelBot(int indice) {
        int posicion = indice % 31;
        if (posicion < 1) {
            return NivelDeGravedad.RIESGO_VITAL_INMEDIATO;
        }
        if (posicion < 4) {
            return NivelDeGravedad.MUY_URGENTE;
        }
        if (posicion < 10) {
            return NivelDeGravedad.URGENTE;
        }
        if (posicion < 20) {
            return NivelDeGravedad.NORMAL;
        }
        return NivelDeGravedad.NO_URGENTE;
    }

    private static NivelDeGravedad nivelMedico(int indice) {
        int posicion = indice % 20;
        if (posicion < 1) {
            return NivelDeGravedad.RIESGO_VITAL_INMEDIATO;
        }
        if (posicion < 3) {
            return NivelDeGravedad.MUY_URGENTE;
        }
        if (posicion < 7) {
            return NivelDeGravedad.URGENTE;
        }
        if (posicion < 13) {
            return NivelDeGravedad.NORMAL;
        }
        return NivelDeGravedad.NO_URGENTE;
    }

    private static int prioridad(NivelDeGravedad nivel) {
        return switch (nivel) {
            case RIESGO_VITAL_INMEDIATO -> 5;
            case MUY_URGENTE -> 4;
            case URGENTE -> 3;
            case NORMAL -> 2;
            case NO_URGENTE -> 1;
        };
    }

    private static EstadoEntradaCola estadoNoAtendido(int indice) {
        return switch (indice % 6) {
            case 0 -> EstadoEntradaCola.EN_COLA;
            case 1 -> EstadoEntradaCola.EN_ESPERA;
            case 2 -> EstadoEntradaCola.ATRASADO;
            case 3 -> EstadoEntradaCola.LLAMADO;
            case 4 -> EstadoEntradaCola.CANCELADA;
            default -> EstadoEntradaCola.EN_ATENCION;
        };
    }

    private static TipoPausaCola tipoPausa(EstadoEntradaCola estado) {
        return switch (estado) {
            case EN_ESPERA -> TipoPausaCola.ESPERA_MANUAL;
            case ATRASADO -> TipoPausaCola.ATRASADO_CONFIRMADO;
            case LLAMADO -> TipoPausaCola.AUSENTE_AL_LLAMADO;
            default -> null;
        };
    }

    private static LocalDateTime fechaHoraLlamado(EstadoEntradaCola estado, boolean atendido,
            LocalDateTime inicioAtencion, LocalDateTime ingreso) {
        if (atendido) {
            return inicioAtencion.minusMinutes(5);
        }
        if (estado == EstadoEntradaCola.LLAMADO) {
            return ingreso.plusMinutes(30);
        }
        return null;
    }
}
