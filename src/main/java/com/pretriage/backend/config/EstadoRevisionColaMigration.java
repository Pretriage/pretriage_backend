package com.pretriage.backend.config;

import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Hibernate update no reemplaza los CHECK de enumeraciones ya creados en PostgreSQL.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class EstadoRevisionColaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ampliarCheck("entrada_cola", "estado", EstadoEntradaCola.values());
        ampliarCheck("consulta_medica", "estado_consulta", EstadoConsulta.values());
    }

    private void ampliarCheck(String tabla, String columna, Enum<?>[] estados) {
        String nombre = tabla + "_" + columna + "_check";
        boolean checkAnterior = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from pg_constraint
                    where conrelid = to_regclass(?) and conname = ? and contype = 'c'
                      and pg_get_constraintdef(oid) not like '%EN_REVISION%'
                )
                """, Boolean.class, tabla, nombre));
        if (!checkAnterior) {
            return;
        }
        String valores = Arrays.stream(estados)
                .map(estado -> "'" + estado.name() + "'")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("alter table " + tabla + " drop constraint " + nombre
                + ", add constraint " + nombre + " check (" + columna + " in (" + valores + "))");
    }
}
