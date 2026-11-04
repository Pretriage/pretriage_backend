package com.pretriage.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts the former medical coordinator role into hospital administration.
 * Remove after every deployed database has run this version at least once.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class CoordinatorRoleMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbcTemplate.update("""
                insert into membresia_hospital_rol (membresia_id, rol)
                select source.membresia_id, 'ADMIN_HOSPITAL'
                from membresia_hospital_rol source
                where source.rol = 'COORDINADOR_MEDICO'
                  and not exists (
                    select 1 from membresia_hospital_rol target
                    where target.membresia_id = source.membresia_id
                      and target.rol = 'ADMIN_HOSPITAL'
                  )
                """);
        jdbcTemplate.update("delete from membresia_hospital_rol where rol = 'COORDINADOR_MEDICO'");

        jdbcTemplate.update("""
                insert into invitacion_hospital_rol (invitacion_id, rol)
                select source.invitacion_id, 'ADMIN_HOSPITAL'
                from invitacion_hospital_rol source
                where source.rol = 'COORDINADOR_MEDICO'
                  and not exists (
                    select 1 from invitacion_hospital_rol target
                    where target.invitacion_id = source.invitacion_id
                      and target.rol = 'ADMIN_HOSPITAL'
                  )
                """);
        jdbcTemplate.update("delete from invitacion_hospital_rol where rol = 'COORDINADOR_MEDICO'");
    }
}
