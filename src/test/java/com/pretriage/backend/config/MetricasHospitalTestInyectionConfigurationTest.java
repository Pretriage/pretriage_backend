package com.pretriage.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class MetricasHospitalTestInyectionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MetricasHospitalTestInyection.class);

    @Test
    void runnerIsAbsentWhenActivationPropertyIsMissing() {
        contextRunner.run(context -> assertFalse(context.containsBean("metricasHospitalTestInyection")));
    }

    @Test
    void runnerIsAbsentWhenActivationPropertyIsFalse() {
        contextRunner
                .withPropertyValues("pretriage.seed.metricas-demo.enabled=false")
                .run(context -> assertFalse(context.containsBean("metricasHospitalTestInyection")));
    }

    @Test
    void runnerExistsWhenExplicitlyEnabledWithoutExecutingSeedSql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        contextRunner
                .withPropertyValues(
                        "pretriage.seed.metricas-demo.enabled=true",
                        "pretriage.seed.metricas-demo.hospital-place-id=demo-hospital-place-id")
                .withBean(JdbcTemplate.class, () -> jdbcTemplate)
                .run(context -> {
                    assertTrue(context.containsBean("metricasHospitalTestInyection"));
                    verify(jdbcTemplate).afterPropertiesSet();
                    verifyNoMoreInteractions(jdbcTemplate);
                });
    }
}
