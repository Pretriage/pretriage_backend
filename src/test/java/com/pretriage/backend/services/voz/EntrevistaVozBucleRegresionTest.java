package com.pretriage.backend.services.voz;

import com.pretriage.backend.controllers.dtos.ResumenEntrevistaVoz;
import com.pretriage.backend.controllers.dtos.TurnoVoz;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.services.entrevista.ProgresoEntrevista;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Dimension.INICIO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Dimension.INTENSIDAD;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Dimension.IMPACTO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Dimension.TEMPERATURA;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Estado.AGOTADO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Estado.PENDIENTE;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Estado.RESPONDIDO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.Estado.DESCONOCIDO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.ALARMA;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.MotivoCierre.EN_CURSO;
import static com.pretriage.backend.services.entrevista.ProgresoEntrevista.TipoObjetivo.DATO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end history projections for the voice interview's repeated-question regression. */
class EntrevistaVozBucleRegresionTest {

    @Test
    void alarmaPosteriorCierraConInicioIntensidadYTemperaturaYaResueltos() {
        List<TurnoVoz> historial = List.of(
                paciente("Me duele la cabeza y tengo fiebre"),
                bot("¿Desde cuándo empezaron estos síntomas?"),
                paciente("Desde ayer en la noche"),
                bot("Del 1 al 10, ¿cuánto te duele la cabeza?"),
                paciente("8"),
                bot("¿Te tomaste la temperatura?"),
                paciente("38° y medio"),
                bot("¿Hay algo más que quieras contar?"),
                paciente("Me falta un poco el aire"));

        ProgresoEntrevista progreso = ProgresoEntrevista.evaluar(historial);

        assertEquals(ALARMA, progreso.motivoCierre());
        assertTrue(progreso.puedeCerrar());
        assertEquals(RESPONDIDO, dato(progreso, "dolor:cabeza", INICIO).estado());
        assertEquals(RESPONDIDO, dato(progreso, "fiebre", INICIO).estado());
        assertTrue(dato(progreso, "dolor:cabeza", INICIO).valor().contains("ayer"));
        assertEquals(dato(progreso, "dolor:cabeza", INICIO).valor(), dato(progreso, "fiebre", INICIO).valor());
        assertEquals("8", dato(progreso, "dolor:cabeza", INTENSIDAD).valor());
        assertEquals(RESPONDIDO, dato(progreso, "fiebre", TEMPERATURA).estado());
        assertEquals("38.5", dato(progreso, "fiebre", TEMPERATURA).valor());
        assertTrue(ValidadorCierreEntrevistaVoz.validar(historial, resumenValido()).isEmpty());
    }

    @Test
    void ausenciaDeOtrosSintomasPermiteCerrarConDatosResueltos() {
        List<TurnoVoz> historial = List.of(
                paciente("Me duele la cabeza y tengo fiebre"),
                bot("¿Desde cuándo empezaron estos síntomas?"),
                paciente("Desde ayer en la noche"),
                bot("Del 1 al 10, ¿cuánto te duele la cabeza?"),
                paciente("8"),
                bot("¿Te tomaste la temperatura?"),
                paciente("38 grados y medio"),
                bot("¿Hay algo más que quieras contar?"),
                paciente("No tengo otros síntomas"));

        ProgresoEntrevista progreso = ProgresoEntrevista.evaluar(historial);

        assertEquals(DATOS_RESUELTOS, progreso.motivoCierre());
        assertTrue(progreso.puedeCerrar());
        assertEquals(RESPONDIDO, dato(progreso, "dolor:cabeza", INICIO).estado());
        assertEquals(RESPONDIDO, dato(progreso, "fiebre", INICIO).estado());
        assertEquals("8", dato(progreso, "dolor:cabeza", INTENSIDAD).valor());
        assertEquals("38.5", dato(progreso, "fiebre", TEMPERATURA).valor());
        assertTrue(ValidadorCierreEntrevistaVoz.validar(historial, resumenValido()).isEmpty());
    }

    @Test
    void termometroAceptaParafrasisYAgotaTrasTresRespuestasSinDato() {
        List<TurnoVoz> historial = new ArrayList<>(List.of(
                paciente("Tengo fiebre y tos"),
                bot("¿Desde cuándo empezaron estos síntomas?"),
                paciente("Desde ayer"),
                bot("¿Cuántos grados marcó el termómetro?"),
                paciente("Eh"),
                bot("¿Qué número viste en el termómetro?"),
                paciente("Eh"),
                bot("¿Cuánto marcó el termómetro?"),
                paciente("Eh")));

        ProgresoEntrevista progreso = ProgresoEntrevista.evaluar(historial);

        ProgresoEntrevista.Dato temperatura = dato(progreso, "fiebre", TEMPERATURA);
        assertEquals(AGOTADO, temperatura.estado());
        assertEquals(ProgresoEntrevista.MAX_INTENTOS_POR_DATO, temperatura.intentos());
        assertEquals(PENDIENTE, dato(progreso, "sintoma:tos", IMPACTO).estado());
        assertEquals(EN_CURSO, progreso.motivoCierre());
        assertEquals(DATO, progreso.objetivoDelTurno().tipo());
        assertEquals("sintoma:tos", progreso.objetivoDelTurno().sintomaId());
        assertEquals(IMPACTO, progreso.objetivoDelTurno().dimension());
        assertTrue(progreso.validarPreguntaPropuesta("¿Cuántos grados marcó el termómetro?").isPresent());
    }

    @Test
    void respuestaNoSeAUnaConfirmacionTermometroQuedaDesconocidaSinRepetirla() {
        List<TurnoVoz> historial = List.of(
                paciente("Tengo fiebre"),
                bot("¿Llegaste a tomarte la temperatura?"),
                paciente("No sé"));

        ProgresoEntrevista progreso = ProgresoEntrevista.evaluar(historial);

        ProgresoEntrevista.Dato temperatura = dato(progreso, "fiebre", TEMPERATURA);
        assertEquals(DESCONOCIDO, temperatura.estado());
        assertEquals(0, temperatura.intentos());
        assertEquals(DATO, progreso.objetivoDelTurno().tipo());
        assertEquals(INICIO, progreso.objetivoDelTurno().dimension());
        assertFalse(progreso.instruccionSiguientePaso().contains("tomaste la temperatura"));
    }

    @Test
    void unSintomaAgregadoDespuesNoHeredaElInicioDelDolorYaCuantificado() {
        List<TurnoVoz> historial = List.of(
                paciente("Me duele la cabeza y tengo fiebre"),
                bot("¿Desde cuándo empezaron estos síntomas?"),
                paciente("Desde ayer en la noche"),
                bot("Del 1 al 10, ¿cuánto te duele la cabeza?"),
                paciente("8"),
                bot("¿Te tomaste la temperatura?"),
                paciente("38° y medio"),
                bot("¿Hay algo más que quieras contar?"),
                paciente("También tengo tos"));

        ProgresoEntrevista progreso = ProgresoEntrevista.evaluar(historial);

        assertTrue(dato(progreso, "dolor:cabeza", INICIO).valor().contains("ayer"));
        assertEquals(dato(progreso, "dolor:cabeza", INICIO).valor(), dato(progreso, "fiebre", INICIO).valor());
        assertEquals(PENDIENTE, dato(progreso, "sintoma:tos", INICIO).estado());
        assertNull(dato(progreso, "sintoma:tos", INICIO).valor());
    }

    private static ProgresoEntrevista.Dato dato(ProgresoEntrevista progreso, String sintoma,
                                                 ProgresoEntrevista.Dimension dimension) {
        return progreso.datos().stream()
                .filter(dato -> dato.sintomaId().equals(sintoma) && dato.dimension() == dimension)
                .findFirst().orElseThrow();
    }

    private static TurnoVoz paciente(String texto) { return new TurnoVoz(AutorMensaje.PACIENTE, texto); }
    private static TurnoVoz bot(String texto) { return new TurnoVoz(AutorMensaje.BOT, texto); }

    private static ResumenEntrevistaVoz resumenValido() {
        return new ResumenEntrevistaVoz("dolor de cabeza y fiebre", List.of("dolor de cabeza", "fiebre"),
                "desde ayer en la noche", "sin cambios", 8, List.of(), List.of(), List.of(), List.of(),
                "no aplica", "sin datos adicionales");
    }
}
