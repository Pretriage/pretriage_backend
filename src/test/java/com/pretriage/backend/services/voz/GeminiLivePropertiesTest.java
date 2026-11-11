package com.pretriage.backend.services.voz;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiLivePropertiesTest {

    @Test
    void sinConfiguracionUsaLaDeteccionDeVozPensadaParaLugaresRuidosos() {
        GeminiLiveProperties properties = new GeminiLiveProperties(
                "clave", "models/live", "wss://gemini.test", "Kore", "es", null);

        assertEquals("START_SENSITIVITY_LOW", properties.sensibilidadInicioVoz());
        assertEquals("END_SENSITIVITY_LOW", properties.sensibilidadFinVoz());
        assertEquals(300, properties.margenPrevioMs());
        assertEquals(900, properties.silencioFinTurnoMs());
        assertEquals("NO_INTERRUPTION", properties.manejoActividad());
        assertTrue(properties.vocabulario().contains("dolor de pecho"));
        assertEquals(Duration.ofSeconds(10), properties.connectTimeout());
    }

    @Test
    void respetaLosValoresConfiguradosYDescartaTerminosVacios() {
        GeminiLiveProperties properties = new GeminiLiveProperties(
                "clave", "models/live", "wss://gemini.test", "Kore", "es", Duration.ofSeconds(2),
                "START_SENSITIVITY_HIGH", "END_SENSITIVITY_HIGH", 100, 600, "START_OF_ACTIVITY_INTERRUPTS",
                Arrays.asList("fiebre", " ", null));

        assertEquals("START_SENSITIVITY_HIGH", properties.sensibilidadInicioVoz());
        assertEquals(600, properties.silencioFinTurnoMs());
        assertEquals("START_OF_ACTIVITY_INTERRUPTS", properties.manejoActividad());
        assertEquals(java.util.List.of("fiebre"), properties.vocabulario());
    }
}
