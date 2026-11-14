package com.pretriage.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * El código de llamado de las consultas se eliminó del sistema. Hibernate {@code update} no borra
 * columnas, así que se quita la columna (y su restricción de unicidad) de las bases existentes.
 */
@Component
@Order(11)
@RequiredArgsConstructor
public class QuitarCodigoLlamadoMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("alter table if exists consulta_medica drop column if exists codigo_llamado");
    }
}
