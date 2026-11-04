package com.pretriage.backend.repositories;

import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the deterministic SQL fixture emitted by the demo seed CLI against isolated PostgreSQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DemoSeedScriptPersistenceTest {

    private static final String SEED_KEY = "demo-fixture-v1";
    private static final Pattern ISOLATED_DATABASE = Pattern.compile("(?i)^pretriage_(?:test|seed_test)(?:_|$).*");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fixtureSqlPersistsValidIdempotentHospitalAndHistoryData() throws Exception {
        String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(databaseName != null && ISOLATED_DATABASE.matcher(databaseName).matches(),
                "Refusing to execute demo fixture SQL outside an isolated pretriage_test database");

        String sql = fixtureSql();
        executeSql(sql);
        Map<String, Long> firstCounts = registryCounts();

        assertEquals(2L, count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'hospital'", SEED_KEY));
        assertEquals(5L, count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'specialty'", SEED_KEY));
        assertEquals(10L, count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'sector'", SEED_KEY));
        assertEquals(20L, count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'room'", SEED_KEY));
        assertEquals(10L, count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'queue'", SEED_KEY));
        long firstQueueEntryCount = count(
                "SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'queue_entry'", SEED_KEY);
        long firstConsultationCount = count(
                "SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'consultation'", SEED_KEY);
        assertTrue(firstQueueEntryCount >= 100L && firstQueueEntryCount <= 600L,
                "Fixture should create a useful but bounded volume of historical queue rows");
        assertEquals(firstQueueEntryCount, firstConsultationCount);
        assertEquals(24L, count(
                "SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'history_patient'", SEED_KEY));

        List<StoredTriageJson> triageJsonRows = jdbc.query("""
                SELECT chat.resultado_triage_json, consulta.resumen_pretriage_json
                FROM demo_seed_registry registry
                JOIN consulta_medica consulta ON consulta.id = registry.entity_id
                JOIN chat ON chat.id = consulta.id_chat
                WHERE registry.seed_key = ? AND registry.entity_type = 'consultation'
                  AND chat.resultado_triage_json IS NOT NULL
                  AND consulta.resumen_pretriage_json IS NOT NULL
                ORDER BY consulta.id
                LIMIT 1
                """, (rs, rowNum) -> new StoredTriageJson(
                rs.getString("resultado_triage_json"), rs.getString("resumen_pretriage_json")), SEED_KEY);
        assertFalse(triageJsonRows.isEmpty(), "At least one seeded consultation must include a chat triage result");
        StoredTriageJson storedTriageJson = triageJsonRows.get(0);
        assertFalse(storedTriageJson.chatJson() == null || storedTriageJson.chatJson().isBlank());
        assertEquals(storedTriageJson.chatJson(), storedTriageJson.consultationJson());
        TriageResultDTO triage = new ObjectMapper().readValue(storedTriageJson.chatJson(), TriageResultDTO.class);
        assertNotNull(triage);
        assertFalse(triage.motivoConsulta() == null || triage.motivoConsulta().isBlank());
        assertNotNull(triage.nivelPrioridad());
        assertTrue(triage.nivelPrioridad() >= 1 && triage.nivelPrioridad() <= 5);
        assertFalse(triage.recomendacionSeguridad() == null || triage.recomendacionSeguridad().isBlank());

        assertEquals(2L, count("SELECT COUNT(*) FROM hospital WHERE place_id IN ('demo-fixture-aleman', 'demo-fixture-italiano')"));
        assertEquals(10L, count("""
                SELECT COUNT(*) FROM hospital_especialidad_medica hem
                JOIN demo_seed_registry r ON r.entity_id = hem.id_hospital
                WHERE r.seed_key = ? AND r.entity_type = 'hospital'
                """, SEED_KEY));
        assertEquals(10L, count("SELECT COUNT(*) FROM sector WHERE nombre LIKE 'DEMO demo-fixture-v1 %'"));
        assertEquals(20L, count("SELECT COUNT(*) FROM sala WHERE nombre LIKE 'DEMO demo-fixture-v1 %'"));
        assertEquals(10L, count("""
                SELECT COUNT(*) FROM gestor_de_cola g
                JOIN demo_seed_registry r ON r.entity_id = g.id_hospital
                WHERE r.seed_key = ? AND r.entity_type = 'hospital'
                """, SEED_KEY));

        assertEquals(1L, count("SELECT COUNT(*) FROM usuario_auth WHERE id LIKE 'demo-fixture|%' AND rol = 0"));
        assertEquals(10L, count("SELECT COUNT(*) FROM usuario_auth WHERE id LIKE 'demo-fixture|%' AND rol = 1"));
        assertTrue(count("""
                SELECT COUNT(*) FROM membresia_hospital_rol r
                JOIN membresia_hospital m ON m.id = r.membresia_id
                JOIN usuario_auth u ON u.id = m.usuario_auth_id
                WHERE u.id LIKE 'demo-fixture|%' AND r.rol = 'ADMIN_HOSPITAL'
                """) >= 1L);
        assertEquals(4L, count("""
                SELECT COUNT(*) FROM membresia_hospital_rol r
                JOIN membresia_hospital m ON m.id = r.membresia_id
                JOIN usuario_auth u ON u.id = m.usuario_auth_id
                WHERE u.id LIKE 'demo-fixture|%' AND r.rol = 'MEDICO'
                """));
        assertEquals(2L, count("""
                SELECT COUNT(*) FROM membresia_hospital_rol r
                JOIN membresia_hospital m ON m.id = r.membresia_id
                JOIN usuario_auth u ON u.id = m.usuario_auth_id
                WHERE u.id LIKE 'demo-fixture|%' AND r.rol = 'RECEPCIONISTA'
                """));

        assertEquals(firstQueueEntryCount, count("""
                SELECT COUNT(*) FROM entrada_cola ec
                JOIN consulta_medica c ON c.id = ec.id_consulta_medica
                JOIN hospital h ON h.id = c.id_hospital
                WHERE h.place_id IN ('demo-fixture-aleman', 'demo-fixture-italiano')
                  AND ec.fecha_hora_ingreso >= TIMESTAMP '2026-10-01 00:00:00'
                  AND ec.fecha_hora_ingreso < TIMESTAMP '2026-10-31 00:00:00'
                """));
        assertEquals(30L, count("""
                SELECT COUNT(DISTINCT ec.fecha_hora_ingreso::date) FROM entrada_cola ec
                JOIN consulta_medica c ON c.id = ec.id_consulta_medica
                JOIN hospital h ON h.id = c.id_hospital
                WHERE h.place_id IN ('demo-fixture-aleman', 'demo-fixture-italiano')
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM entrada_cola ec
                LEFT JOIN consulta_medica c ON c.id = ec.id_consulta_medica
                LEFT JOIN gestor_de_cola g ON g.id = ec.id_gestor_de_cola
                WHERE c.id IS NULL OR g.id IS NULL
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM atencion_medica a
                LEFT JOIN consulta_medica c ON c.id = a.id_consulta_medica
                LEFT JOIN sesion_atencion_medica s ON s.id = a.id_sesion_atencion_medica
                WHERE c.id IS NULL OR s.id IS NULL
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM atencion_medica a
                JOIN consulta_medica c ON c.id = a.id_consulta_medica
                JOIN sesion_atencion_medica s ON s.id = a.id_sesion_atencion_medica
                JOIN demo_seed_registry r ON r.entity_id = a.id
                WHERE r.seed_key = ? AND r.entity_type = 'attention'
                  AND (a.fecha_hora_inicio < s.fecha_hora_inicio
                    OR a.fecha_hora_fin > s.fecha_hora_fin
                    OR a.fecha_hora_fin <= a.fecha_hora_inicio)
                """, SEED_KEY));
        assertTrue(count("""
                SELECT COUNT(*) FROM entrada_cola ec
                JOIN demo_seed_registry r ON r.entity_id = ec.id
                WHERE r.seed_key = ? AND r.entity_type = 'queue_entry' AND ec.estado = 'FINALIZADA'
                """, SEED_KEY) > 0L);
        assertTrue(count("""
                SELECT COUNT(*) FROM entrada_cola ec
                JOIN demo_seed_registry r ON r.entity_id = ec.id
                WHERE r.seed_key = ? AND r.entity_type = 'queue_entry' AND ec.estado = 'CANCELADA'
                """, SEED_KEY) > 0L);
        assertEquals(count("""
                SELECT COUNT(*) FROM entrada_cola ec
                JOIN demo_seed_registry r ON r.entity_id = ec.id
                WHERE r.seed_key = ? AND r.entity_type = 'queue_entry' AND ec.estado = 'FINALIZADA'
                """, SEED_KEY), count("""
                SELECT COUNT(*) FROM atencion_medica a
                JOIN demo_seed_registry r ON r.entity_id = a.id
                WHERE r.seed_key = ? AND r.entity_type = 'attention' AND a.estado = 'FINALIZADA'
                """, SEED_KEY));

        jdbc.update("INSERT INTO hospital(nombre, place_id) VALUES (?, ?)",
                "Unrelated persistence test sentinel", "persistence-test-sentinel");
        executeSql(sql);
        Map<String, Long> secondCounts = registryCounts();
        assertEquals(firstCounts, secondCounts, "Re-running the same seed must not add duplicate registered rows");
        assertEquals(firstQueueEntryCount,
                count("SELECT COUNT(*) FROM demo_seed_registry WHERE seed_key = ? AND entity_type = 'queue_entry'", SEED_KEY));
        assertEquals(1L, count("SELECT COUNT(*) FROM hospital WHERE place_id = 'persistence-test-sentinel'"));
    }

    private String fixtureSql() throws Exception {
        Process process = new ProcessBuilder("python", "-B", "scripts/test_demo_seed.py", "--fixture-sql")
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        String sql = new String(output, StandardCharsets.UTF_8);
        assertEquals(0, exitCode, "Fixture SQL generator must exit successfully: " + sql);
        assertFalse(sql.isBlank(), "Fixture SQL generator must emit SQL on stdout");
        assertEquals(1L, sql.lines().filter("BEGIN;"::equals).count(), "Fixture must have one transaction start");
        assertEquals(1L, sql.lines().filter("COMMIT;"::equals).count(), "Fixture must have one transaction end");
        // Keep the generated body inside DataJpaTest's transaction. Executing its
        // outer COMMIT here would leak demo data into the other repository tests.
        return String.join("\n", sql.lines()
                .filter(line -> !line.equals("BEGIN;") && !line.equals("COMMIT;"))
                .toList());
    }

    @AfterTransaction
    void fixtureDataIsRolledBack() {
        assertEquals(0L, count("SELECT COUNT(*) FROM hospital WHERE place_id = 'persistence-test-sentinel'"));
        assertEquals(0L, count("SELECT COUNT(*) FROM hospital WHERE place_id IN ('demo-fixture-aleman', 'demo-fixture-italiano')"));
        assertEquals(0L, count("SELECT COUNT(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename IN ('demo_seed_runs', 'demo_seed_registry')"));
    }

    private void executeSql(String sql) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private Map<String, Long> registryCounts() {
        return jdbc.query("""
                SELECT entity_type, COUNT(*)
                FROM demo_seed_registry WHERE seed_key = ?
                GROUP BY entity_type ORDER BY entity_type
                """, rs -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
            }
            return counts;
        }, SEED_KEY);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private record StoredTriageJson(String chatJson, String consultationJson) { }
}
