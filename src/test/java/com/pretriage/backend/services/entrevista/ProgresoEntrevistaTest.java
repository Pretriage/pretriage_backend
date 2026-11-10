package com.pretriage.backend.services.entrevista;

import com.pretriage.backend.controllers.dtos.TurnoVoz;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.services.entrevista.ProgresoEntrevista.Estado;
import com.pretriage.backend.services.voz.ValidadorCierreEntrevistaVoz;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProgresoEntrevistaTest {
    @Test
    void negativaOralResuelveLaInvitacionEnLaPrimeraRespuesta() {
        for (String respuesta : List.of("No, no.", "No. Nada.", "No, nada.", "No, nada más, gracias.")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Me duele la cabeza desde ayer, intensidad 8/10"),
                    b("¿Hay algo más que quieras contar sobre lo que te pasa?"), p(respuesta)));
            assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, progreso.motivoCierre(), respuesta);
            assertFalse(progreso.requiereAclaracion(), respuesta);
            assertFalse(progreso.esperaRespuestaPaciente(), respuesta);
        }
    }

    @Test
    void negativaConUnAporteNoReconocidoNoDescartaEseAporte() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer, intensidad 8/10"),
                b("¿Hay algo más que quieras contar?"), p("No, pero veo luces extrañas.")));
        assertFalse(progreso.puedeCerrar());
        assertTrue(progreso.requiereAclaracion());
    }

    @Test
    void negativaOralNoResuelveUnaIntensidadPendienteNiUnTurnoExcluido() {
        var pendiente = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("¿Hay algo más que quieras contar?"), p("No, nada.")));
        assertFalse(pendiente.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, pendiente.objetivoDelTurno().tipo());

        var excluido = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Me duele la cabeza desde ayer, intensidad 8/10", false),
                bt("¿Hay algo más que quieras contar?"), pt("No, nada.", true)));
        assertFalse(excluido.puedeCerrar());
    }

    @Test
    void cadaDolorLocalizadoConservaSusPropiosDatos() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"),
                b("¿Desde cuándo te duele la cabeza?"), p("Desde ayer")));

        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO, dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE, dato(progreso, "dolor:espalda", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void preguntaSobreSintomaYaResueltoNoConsumeDatoDeOtroSintoma() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"),
                b("¿Desde cuándo te duele la cabeza?"), p("Desde ayer"),
                b("¿Desde cuándo te duele la cabeza?"), p("Desde hoy")));

        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(progreso, "dolor:espalda", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void dolorEnumeradoEnUnaFraseMantieneObjetivosSeparados() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"),
                b("¿Desde cuándo te duele la espalda?"), p("Desde hoy")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "dolor:espalda", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void inicioDeFiebreNoCompletaElInicioDelDolorPreguntado() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Desde cuándo te duele la cabeza?"), p("Tengo fiebre desde ayer")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void onsetCompartidoSoloSeTransfiereCuandoLaPreguntaYRespuestaDicenAmbos() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Cuándo comenzaron ambos síntomas?"), p("Ambos comenzaron ayer")));
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void referenciaAmbosNoAdivinaUnParSiHayTresIniciosPendientesSinNombres() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza, tengo fiebre y tos"),
                b("¿Cuándo comenzaron ambos síntomas?"), p("Desde ayer")));

        assertEquals(Estado.PENDIENTE,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void preguntaDeInicioSobreEstosSintomasComparteSoloLosYaReferidos() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Desde cuándo tenés estos síntomas?"), p("Desde ayer en la noche"),
                p("También tengo náuseas")));

        var inicioDolor = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO);
        var inicioFiebre = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(Estado.RESPONDIDO, inicioDolor.estado());
        assertEquals("desde ayer en la noche", inicioDolor.valor());
        assertEquals(Estado.RESPONDIDO, inicioFiebre.estado());
        assertEquals("desde ayer en la noche", inicioFiebre.valor());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "sintoma:nauseas", ProgresoEntrevista.Dimension.INICIO).estado(),
                "Un síntoma agregado después de la respuesta no hereda el inicio compartido anterior.");

        var inicioEspecifico = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Desde cuándo te duele la cabeza?"), p("Desde ayer en la noche")));
        assertEquals(Estado.RESPONDIDO,
                dato(inicioEspecifico, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.PENDIENTE,
                dato(inicioEspecifico, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado(),
                "Una respuesta a una pregunta específica no se transfiere a otro síntoma.");
    }

    @Test
    void preguntaNaturalPorHorasODiasVinculaLaDuracionAlDolorYPermiteCerrarTrasLaInvitacion() {
        var preguntaInicio = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza")));
        assertTrue(preguntaInicio.validarPreguntaPropuesta(
                "¿Podrías precisarme aproximadamente cuántas horas o días hace que empezó ese dolor?").isEmpty());

        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"),
                b("¿Podrías precisarme aproximadamente cuántas horas o días hace que empezó ese dolor?"),
                p("hace 2 años y 1 día"),
                b("¿Este dolor te impide realizar tus actividades habituales?"), p("Sí."),
                b("¿Cuánto te duele del 1 al 10?"), p("4"),
                b("¿Hay algo más que quieras contar?"), p("No")));

        var inicio = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(Estado.RESPONDIDO, inicio.estado());
        assertEquals("hace 2 anos y 1 dia", inicio.valor());
        var impacto = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.IMPACTO);
        assertEquals(Estado.RESPONDIDO, impacto.estado());
        assertEquals("si.", impacto.valor());
        assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, progreso.motivoCierre());
    }

    @Test
    void solicitudExplicitaDeTerminarReconoceVariantesSinTomarCitasHipotesisNiExclusiones() {
        for (String frase : List.of("Termina el chat", "Terminá el chat", "Finalizá", "Finalizá el chat",
                "Quiero terminar la entrevista", "No quiero seguir porque estoy cansado",
                "No quiero seguir respondiendo", "No quiero seguir respondiendo preguntas",
                "Quiero terminar ahora", "Por favor, quiero terminar")) {
            assertEquals(ProgresoEntrevista.MotivoCierre.SOLICITUD_PACIENTE,
                    ProgresoEntrevista.evaluar(turnos(p(frase))).motivoCierre(), frase);
        }

        for (String frase : List.of("No quiero terminar la entrevista",
                "Si termino el chat, ¿qué pasa?", "Si finalizo el chat, ¿qué pasa?",
                "Ejemplo: quiero terminar la entrevista", "«Quiero terminar la entrevista»",
                "BOT: finaliza")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(p(frase)));
            assertNotEquals(ProgresoEntrevista.MotivoCierre.SOLICITUD_PACIENTE,
                    progreso.motivoCierre(), frase);
        }

        var excluido = ProgresoEntrevista.evaluarConContexto(List.of(
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Finalizá", true)));
        assertNotEquals(ProgresoEntrevista.MotivoCierre.SOLICITUD_PACIENTE, excluido.motivoCierre());
    }

    @Test
    void aporteOpacoTrasInvitacionPideUnaAclaracionYLuegoCierraParcialSinRepetirInvitacion() {
        var historial = new java.util.ArrayList<>(turnos(
                p("Tengo tos desde ayer"),
                b("¿Te afecta trabajar por la tos?"), p("Me cuesta trabajar por la tos."),
                b("¿Hay algo más que quieras contar?"), p("Ojos resecos.")));

        var esperaAclaracion = ProgresoEntrevista.evaluar(historial);
        assertFalse(esperaAclaracion.puedeCerrar());
        assertTrue(esperaAclaracion.requiereAclaracion());
        assertFalse(esperaAclaracion.esperaRespuestaPaciente());
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, esperaAclaracion.objetivoDelTurno().tipo());
        assertTrue(esperaAclaracion.instruccionSiguientePaso().contains("una sola aclaración abierta"));
        assertTrue(esperaAclaracion.datos().stream().noneMatch(d -> d.sintomaId().contains("ojo")));

        var conAcuse = new java.util.ArrayList<>(historial);
        conAcuse.add(b("Entiendo, gracias. Estoy a tu disposición."));
        assertFalse(ProgresoEntrevista.evaluar(conAcuse).esperaRespuestaPaciente());

        var conInstruccion = new java.util.ArrayList<>(historial);
        conInstruccion.add(b("Contame con tus palabras qué más te ocurre."));
        assertTrue(ProgresoEntrevista.evaluar(conInstruccion).esperaRespuestaPaciente());

        historial.add(b("¿Podés aclarar con tus palabras qué más te ocurre?"));
        var preguntaAbierta = ProgresoEntrevista.evaluar(historial);
        assertTrue(preguntaAbierta.esperaRespuestaPaciente());

        historial.add(p("Ojos resecos."));
        var parcial = ProgresoEntrevista.evaluar(historial);
        assertTrue(parcial.puedeCerrar());
        assertEquals(ProgresoEntrevista.MotivoCierre.LIMITE_RECURSOS, parcial.motivoCierre());
        assertFalse(parcial.esperaRespuestaPaciente());
        assertFalse(parcial.requiereAclaracion());
        assertTrue(parcial.datos().stream().noneMatch(d -> d.sintomaId().contains("ojo")));
        assertTrue(parcial.instruccionSiguientePaso().contains("Conserva literalmente el aporte"));

        historial.add(p("Me duele el ojo desde hoy."));
        var recuperado = ProgresoEntrevista.evaluar(historial);
        assertFalse(recuperado.puedeCerrar());
        assertEquals(ProgresoEntrevista.MotivoCierre.EN_CURSO, recuperado.motivoCierre());
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "dolor:ojo", ProgresoEntrevista.Dimension.INTENSIDAD), recuperado.objetivoDelTurno());

        historial.add(b("Del 1 al 10, ¿cuánto te duele el ojo?"));
        historial.add(p("3"));
        var cerrado = ProgresoEntrevista.evaluar(historial);
        assertTrue(cerrado.puedeCerrar());
        assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, cerrado.motivoCierre());
        assertEquals(ProgresoEntrevista.TipoObjetivo.CERRAR, cerrado.objetivoDelTurno().tipo());
    }

    @Test
    void aclaracionDeInvitacionRecuperaSintomaReconocidoYCortesiaNoReabreElCierre() {
        var historial = new java.util.ArrayList<>(turnos(
                p("Tengo tos desde ayer"), b("¿Te afecta trabajar por la tos?"), p("Me cuesta trabajar por la tos."),
                b("¿Hay algo más que quieras contar?"), p("Ojos resecos."),
                b("¿Podés aclarar con tus palabras qué más te ocurre?"), p("También me duele la garganta.")));
        var recuperado = ProgresoEntrevista.evaluar(historial);
        assertFalse(recuperado.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, recuperado.objetivoDelTurno().tipo());
        assertTrue(recuperado.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:garganta")));

        for (String respuesta : List.of("No sé", "No quiero agregar", "No, nada más.", "Gracias")) {
            var cerrado = ProgresoEntrevista.evaluar(turnos(
                    p("Tengo tos desde ayer"), b("¿Te afecta trabajar por la tos?"), p("Me cuesta trabajar por la tos."),
                    b("¿Hay algo más que quieras contar?"), p(respuesta)));
            assertTrue(cerrado.puedeCerrar(), respuesta);
            assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, cerrado.motivoCierre(), respuesta);
            assertEquals(2, cerrado.datos().size(), respuesta);
            assertFalse(cerrado.esperaRespuestaPaciente(), respuesta);
        }

        var declinacionTrasAclaracion = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos desde ayer"), b("¿Te afecta trabajar por la tos?"), p("Me cuesta trabajar por la tos."),
                b("¿Hay algo más que quieras contar?"), p("Ojos resecos."),
                b("¿Podés aclarar con tus palabras qué más te ocurre?"), p("No sé")));
        assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS,
                declinacionTrasAclaracion.motivoCierre());
        assertFalse(declinacionTrasAclaracion.requiereAclaracion());
    }

    @Test
    void evolucionBreveDelUnicoMotivoConocidoRespondeLaInvitacionSinInventarUnDato() {
        for (String respuesta : List.of("Sigue igual.", "Sin cambios.", "Sigue mejor.", "Sigue peor.")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Tengo dolor de garganta desde ayer."), p("Es 5 de 10."),
                    b("¿Hay algo más que quieras contar sobre lo que te pasa?"), p(respuesta)));

            assertTrue(progreso.puedeCerrar(), respuesta);
            assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, progreso.motivoCierre(), respuesta);
            assertEquals(2, progreso.datos().size(), respuesta);
            assertEquals("5", dato(progreso, "dolor:garganta", ProgresoEntrevista.Dimension.INTENSIDAD).valor(), respuesta);
            assertFalse(progreso.requiereAclaracion(), respuesta);
        }

        var conAporteNuevo = ProgresoEntrevista.evaluar(turnos(
                p("Tengo dolor de garganta desde ayer."), p("Es 5 de 10."),
                b("¿Hay algo más que quieras contar sobre lo que te pasa?"),
                p("Sigue igual, además tengo los ojos resecos.")));
        assertFalse(conAporteNuevo.puedeCerrar());
        assertTrue(conAporteNuevo.requiereAclaracion());
        assertTrue(conAporteNuevo.datos().stream().noneMatch(d -> d.sintomaId().contains("ojo")));
    }

    @Test
    void esperaRespuestaPacienteSoloParaObjetivosActivosNoAgotadosYNoConfirmados() {
        var intensidadAntesDeInicio = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("Del 1 al 10, dime la intensidad del dolor de cabeza")));
        assertTrue(intensidadAntesDeInicio.esperaRespuestaPaciente(),
                "La dimensión pendiente se espera aunque el inicio siga pendiente y no haya signo de pregunta.");

        var datoResuelto = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("¿Desde cuándo empezó el dolor de cabeza?")));
        assertFalse(datoResuelto.esperaRespuestaPaciente());

        var agotada = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("Eh"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("Eh"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("Eh"),
                b("Del 1 al 10, dime la intensidad del dolor de cabeza")));
        assertEquals(Estado.AGOTADO,
                dato(agotada, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertFalse(agotada.esperaRespuestaPaciente());

        var confirmacionRepetida = ProgresoEntrevista.evaluar(turnos(
                p("Tengo fiebre"),
                b("¿Pudiste medirte la temperatura con un termómetro?"), p("Sí, ya la medí"),
                b("¿Pudiste medirte la temperatura con un termómetro?")));
        assertFalse(confirmacionRepetida.esperaRespuestaPaciente());
    }

    @Test
    void impactoAceptaCuantificadorConExplicacionFuncionalAfirmativaYConservaLaRespuesta() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos"),
                b("¿Cuánto te afecta la tos para hacer tus actividades?"),
                p("Poco, puedo hacer mis actividades.")));

        var impacto = dato(progreso, "sintoma:tos", ProgresoEntrevista.Dimension.IMPACTO);
        assertEquals(Estado.RESPONDIDO, impacto.estado());
        assertEquals("poco, puedo hacer mis actividades.", impacto.valor());
    }

    @Test
    void impactoCompuestoNoAceptaEscalasUnidadesNegacionesNiOtroSintoma() {
        for (String respuesta : List.of(
                "8, puedo hacer mis actividades.",
                "Poco, no puedo hacer mis actividades.",
                "Poco, puedo hacer mis actividades con 8 cm.")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Tengo tos"),
                    b("¿Cuánto te afecta la tos para hacer tus actividades?"),
                    p(respuesta)));
            assertEquals(Estado.DESCONOCIDO,
                    dato(progreso, "sintoma:tos", ProgresoEntrevista.Dimension.IMPACTO).estado(), respuesta);
        }

        var otroSintoma = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos y fiebre"),
                b("¿Cuánto te afecta la tos para hacer tus actividades?"),
                p("Poco, puedo hacer mis actividades por la fiebre.")));
        assertEquals(Estado.DESCONOCIDO,
                dato(otroSintoma, "sintoma:tos", ProgresoEntrevista.Dimension.IMPACTO).estado());
    }

    @Test
    void duracionRelativaSeConservaLiteralYSinTransferirseAOtroSintoma() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"),
                b("¿Desde cuándo empezó el dolor de cabeza?"), p("Desde que me dejó")));

        assertEquals("desde que me dejo", dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).valor());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "dolor:espalda", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void referenciaRelativaDesdeQueSeFueResuelveDolorGenericoSoloBajoPreguntaDeInicio() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Es un dolor reprofundo y tengo fiebre"),
                b("¿Podrías decirme cuántas horas o días hace que empezó ese dolor?"),
                p("Desde que se fue")));

        assertEquals("desde que se fue",
                dato(progreso, "dolor:general", ProgresoEntrevista.Dimension.INICIO).valor());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado());

        var espontaneo = ProgresoEntrevista.evaluar(turnos(p("Tengo dolor de cabeza desde que se fue")));
        assertEquals(Estado.PENDIENTE,
                dato(espontaneo, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void desdeQueMeDejoConservaLaResolucionEspontaneaDelSintomaNombradoSinTransferencia() {
        var explicito = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza desde que me dejó")));
        var inicioExplicito = dato(explicito, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(Estado.RESPONDIDO, inicioExplicito.estado());
        assertEquals("desde que me dejo", inicioExplicito.valor());

        var separados = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza; tengo fiebre desde que me dejó")));
        assertEquals(Estado.PENDIENTE,
                dato(separados, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals("desde que me dejo",
                dato(separados, "fiebre", ProgresoEntrevista.Dimension.INICIO).valor());
    }

    @Test
    void incertidumbreDeFechaNoBorraInicioRelativoAfirmadoEnLaRespuestaDeInicio() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos"),
                b("¿Desde cuándo empezó la tos?"),
                p("No sé la fecha exacta; la tos empezó desde que mi pareja se mudó a otra ciudad.")));

        var inicio = dato(progreso, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(Estado.RESPONDIDO, inicio.estado());
        assertEquals("desde que mi pareja se mudo a otra ciudad", inicio.valor());
    }

    @Test
    void incertidumbreONegacionDelInicioNoSeConviertenEnTiempoAfirmado() {
        var incierto = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos"), b("¿Desde cuándo empezó la tos?"),
                p("No sé si la tos empezó desde que mi pareja se mudó a otra ciudad.")));
        var inicioIncierto = dato(incierto, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO);
        assertNotEquals(Estado.RESPONDIDO, inicioIncierto.estado());

        var negado = ProgresoEntrevista.evaluar(turnos(
                p("Tengo tos"), b("¿Desde cuándo empezó la tos?"),
                p("La tos no empezó desde que mi pareja se mudó a otra ciudad.")));
        var inicioNegado = dato(negado, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO);
        assertNotEquals(Estado.RESPONDIDO, inicioNegado.estado());
    }

    @Test
    void fraseExactaConNoLoSeYDesdeQueEllaMeDejoConservaElInicioAfirmado() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo una molestia"), b("¿Desde cuándo empezó esta molestia?"),
                p("No lo sé, lo siento desde que ella me dejó.")));

        var inicio = dato(progreso, "sintoma:molestia", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(Estado.RESPONDIDO, inicio.estado());
        assertEquals("desde que ella me dejo", inicio.valor());
    }

    @Test
    void incertidumbreYNegacionSobreEllaNoAfirmanElInicio() {
        var sentimientoHipotetico = ProgresoEntrevista.evaluar(turnos(
                p("Tengo una molestia"), b("¿Desde cuándo empezó esta molestia?"),
                p("No sé si lo siento desde que ella me dejó.")));
        assertNotEquals(Estado.RESPONDIDO,
                dato(sentimientoHipotetico, "sintoma:molestia", ProgresoEntrevista.Dimension.INICIO).estado());

        var incierto = ProgresoEntrevista.evaluar(turnos(
                p("Tengo una molestia"), b("¿Desde cuándo empezó esta molestia?"),
                p("No sé si empezó desde que ella me dejó.")));
        assertNotEquals(Estado.RESPONDIDO,
                dato(incierto, "sintoma:molestia", ProgresoEntrevista.Dimension.INICIO).estado());

        var negado = ProgresoEntrevista.evaluar(turnos(
                p("Tengo una molestia"), b("¿Desde cuándo empezó esta molestia?"),
                p("La molestia no empezó desde que ella me dejó.")));
        assertNotEquals(Estado.RESPONDIDO,
                dato(negado, "sintoma:molestia", ProgresoEntrevista.Dimension.INICIO).estado());
    }

    @Test
    void respuestaExcluidaALaPreguntaNaturalNoResuelveNiTransfiereElInicio() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Me duele la cabeza y tengo fiebre", false),
                bt("¿Podrías decirme cuántas horas o días hace que empezó el dolor de cabeza?"),
                pt("hace 2 años y 1 día", true)));

        assertEquals(Estado.PENDIENTE,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(1, dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).intentos());
    }

    @Test
    void aclaracionAceptaParafrasisAbiertaDelMismoInicioPeroNoIntroduceOtroSintoma() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"), b("¿Desde cuándo empezó el dolor de cabeza?"), p("Quizás")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, progreso.objetivoDelTurno().tipo());
        assertTrue(progreso.validarPreguntaPropuesta(
                "¿Podrías contarme con tus palabras cuánto tiempo lleva ese dolor de cabeza?").isEmpty());
        assertEquals("SINTOMA_NO_COINCIDE", progreso.validarPreguntaPropuesta(
                "¿Podrías contarme con tus palabras cuánto tiempo lleva ese dolor de espalda?").orElseThrow());
    }

    @Test
    void fiebreNoSeResuelveConNumeroDePesoNiUnaMedicionSinValor() {
        var peso = ProgresoEntrevista.evaluar(turnos(p("Tengo fiebre"), b("¿Qué temperatura mediste?"), p("38 kg")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(peso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
        assertNull(dato(peso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).valor());

        var sinValor = ProgresoEntrevista.evaluar(turnos(p("Tengo fiebre"), b("¿Qué temperatura mediste?"), p("Sí, me medí")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(sinValor, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
    }

    @Test
    void temperaturasConGradoDecimalOMedioSeNormalizanSinPerderElValor() {
        List<String[]> medidas = List.of(
                new String[]{"38°", "38"},
                new String[]{"38,5°", "38.5"},
                new String[]{"38° y medio", "38.5"},
                new String[]{"38 grados y medio", "38.5"},
                new String[]{"38,5 grados", "38.5"},
                new String[]{"Sí tengo 38° y medio", "38.5"});

        for (String[] medida : medidas) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Tengo fiebre"), b("¿Qué temperatura marcó el termómetro?"), p(medida[0])));
            var temperatura = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
            assertEquals(Estado.RESPONDIDO, temperatura.estado(), medida[0]);
            assertEquals(medida[1], temperatura.valor(), medida[0]);
        }
    }

    @Test
    void gradosNegadosHipoteticosNoValidosOFahrenheitNoResuelvenTemperatura() {
        for (String respuesta : List.of(
                "38 kg", "38 cm", "38°F", "38 grados Fahrenheit", "38,55°", "38.55 grados",
                "temperatura de38,55°",
                "38,5° y medio", "38° o 39°", "No tengo 38°", "Si tuviera 38°",
                "Si tengo 38°, qué hago?")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Tengo fiebre"), b("¿Qué temperatura marcó el termómetro?"), p(respuesta)));
            var temperatura = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
            assertEquals(Estado.PENDIENTE, temperatura.estado(), respuesta);
            assertNull(temperatura.valor(), respuesta);
        }
    }

    @Test
    void preguntaCuantoMarcoElTermometroSeAsociaAlDatoTemperatura() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Tengo fiebre"),
                b("¿Desde cuándo empezó la fiebre?"), p("Desde ayer"),
                b("¿Cuánto marcó el termómetro?"), p("38")));
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());

        var pendiente = ProgresoEntrevista.evaluar(turnos(p("Tengo fiebre"),
                b("¿Desde cuándo empezó la fiebre?"), p("Desde ayer")));
        assertTrue(pendiente.instruccionSiguientePaso().contains("cuántos grados de temperatura marcó"));
    }

    @Test
    void cefaleaYUnOchoCompartenElMismoObjetivoDeDolor() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Tengo cefalea"),
                b("¿Desde cuándo empezó el dolor de cabeza?"), p("Desde ayer"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("Un ocho")));
        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO, intensidad.estado());
        assertEquals("ocho", intensidad.valor());
    }

    @Test
    void muyIntensoRespondeSoloLaIntensidadDeDolorCuandoLaPreguntaLaContextualiza() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Qué tan intenso es el dolor de cabeza?"),
                p("Muy intenso")));
        assertEquals(Estado.RESPONDIDO,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertEquals("muy intenso",
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
        assertEquals(Estado.PENDIENTE,
                dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());

        var negada = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"),
                b("¿Qué tan intenso es el dolor de cabeza?"),
                p("No es muy intenso")));
        assertEquals(Estado.PENDIENTE,
                dato(negada, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());

        var sinPregunta = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza"), p("Muy intenso")));
        assertEquals(Estado.PENDIENTE,
                dato(sinPregunta, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());

        var ambiguo = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"), p("Muy intenso")));
        assertEquals(Estado.PENDIENTE,
                dato(ambiguo, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertEquals(Estado.PENDIENTE,
                dato(ambiguo, "dolor:espalda", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void numeroAisladoDeUnaReferenciaAMessiNoCompletaLaEscala() {
        var espontaneo = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza, 8 Messi")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(espontaneo, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());

        var contextual = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("8 Messi")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(contextual, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void valorDeEscalaSeInterpretaConLaPreguntaAunqueLaRespuestaContinue() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("Un 6, no tengo alergias.")));
        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO, intensidad.estado());
        assertEquals("6", intensidad.valor());
    }

    @Test
    void escalaConReferenciaGenericaSeVinculaAlUnicoDolorLocalizadoExistente() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza"),
                b("¿Desde cuándo te duele la cabeza?"), p("Desde ayer"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("El dolor es 2/10 y se mantiene.")));
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO,
                dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertTrue(progreso.datos().stream().noneMatch(d -> d.sintomaId().equals("dolor:general")));
    }

    @Test
    void escalaGenericaNoSeTransfiereCuandoHayDosDoloresLocalizados() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza y la espalda"),
                b("Del 1 al 10, ¿cuánto te duele la cabeza?"), p("8"),
                b("Del 1 al 10, ¿cuánto te duele la espalda?"), p("3"),
                p("El dolor es 2/10 y se mantiene.")));
        assertEquals("8", dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
        assertEquals("3", dato(progreso, "dolor:espalda", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
    }

    @Test
    void escalaEspontaneaSeAsociaAlUnicoDolorConocidoSinPreguntaPendiente() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo dolor de garganta desde ayer."), p("Es 5 de 10.")));
        var intensidad = dato(progreso, "dolor:garganta", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(ProgresoEntrevista.Estado.RESPONDIDO, intensidad.estado());
        assertEquals("5", intensidad.valor());

        var ambiguo = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda."), p("Es 5 de 10.")));
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(ambiguo, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertEquals(ProgresoEntrevista.Estado.PENDIENTE,
                dato(ambiguo, "dolor:espalda", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void intensidadLocalNoTomaAdjetivosDeFiebreYAceptaEscalaEspontaneaExplicita() {
        var separada = ProgresoEntrevista.evaluar(turnos(p("Me duele la cabeza desde ayer, 5 de 10")));
        assertEquals("5", dato(separada, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());

        var fiebre = ProgresoEntrevista.evaluar(turnos(
                p("Tengo fiebre fuerte desde ayer y dolor de cabeza desde ayer")));
        assertEquals(Estado.PENDIENTE,
                dato(fiebre, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());

        var espontanea = ProgresoEntrevista.evaluar(turnos(p("Tengo dolor de garganta desde ayer"),
                p("Empeoro un poco, intensidad 5 de 10")));
        assertEquals("5", dato(espontanea, "dolor:garganta", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
    }

    @Test
    void reconoceIniciosColoquialesYNoCreaSintomasNegadosNiDuplicadosPorCausa() {
        var mareo = ProgresoEntrevista.evaluar(turnos(p("Tengo dolor de panza y mareo desde hace un rato")));
        assertEquals(Estado.PENDIENTE,
                dato(mareo, "dolor:abdomen", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.RESPONDIDO,
                dato(mareo, "sintoma:mareos", ProgresoEntrevista.Dimension.INICIO).estado());
        assertTrue(mareo.datos().stream().noneMatch(d -> d.sintomaId().equals("dolor:general")));

        var amanecer = ProgresoEntrevista.evaluar(turnos(p("Tengo tos desde el amanecer")));
        assertEquals(Estado.RESPONDIDO,
                dato(amanecer, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO).estado());

        var negados = ProgresoEntrevista.evaluar(turnos(p(
                "No, no tengo más síntomas. No tengo dificultad para respirar, dolor de pecho o confusion ni antecedentes, alergias o medicacion.")));
        assertTrue(negados.datos().stream().noneMatch(d -> d.sintomaId().equals("dolor:pecho")));

        var inciertos = ProgresoEntrevista.evaluar(turnos(p("No sé si tengo dolor de pecho. No recuerdo haber tenido fiebre.")));
        assertTrue(inciertos.datos().stream().noneMatch(d -> d.sintomaId().equals("dolor:pecho") || d.sintomaId().equals("fiebre")));

        var golpeCausal = ProgresoEntrevista.evaluar(turnos(p(
                "Un moraton en la rodilla por un golpe hace 3 dias y va empeorando")));
        assertTrue(golpeCausal.datos().stream().anyMatch(d -> d.sintomaId().equals("sintoma:hematoma")));
        assertTrue(golpeCausal.datos().stream().noneMatch(d -> d.sintomaId().equals("sintoma:traumatismo")));
    }

    @Test
    void respuestasEspontaneasResuelvenInicioDolorAbdominalYTemperaturaNoMedida() {
        var dolorAbdominal = ProgresoEntrevista.evaluar(turnos(
                p("Dolor de panza y mareo desde hace un rato"),
                p("El dolor empezó suave ayer, intensidad 6")));
        assertEquals(Estado.RESPONDIDO,
                dato(dolorAbdominal, "dolor:abdomen", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.RESPONDIDO,
                dato(dolorAbdominal, "dolor:abdomen", ProgresoEntrevista.Dimension.INTENSIDAD).estado());

        var noMedidaDuranteInicio = ProgresoEntrevista.evaluar(turnos(
                p("Tengo dolor de cabeza y fiebre"),
                b("¿Desde cuándo empezaron el dolor de cabeza y la fiebre?"),
                p("El dolor de cabeza empezó hace 12 horas; la fiebre empezó hace 12 horas y no medí la temperatura"),
                b("Del 0 al 10, ¿cuánto te duele?"), p("8/10")));
        assertEquals(Estado.DESCONOCIDO,
                dato(noMedidaDuranteInicio, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
        assertEquals(Estado.RESPONDIDO,
                dato(noMedidaDuranteInicio, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void escalaDeDolorSeparadaDeFiebreSeAsociaAlDolorYNoSePierdeTrasUnNoSe() {
        var escalaConOtroSintoma = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer, 5 de 10. También tengo fiebre 39 grados")));
        assertEquals("5", dato(escalaConOtroSintoma, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
        assertEquals("39", dato(escalaConOtroSintoma, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).valor());

        var noSe = ProgresoEntrevista.evaluar(turnos(p("Tengo dolor de panza"),
                b("Del 0 al 10, ¿cuánto te duele?"),
                p("No sé bien la intensidad; el dolor empezó suave ayer pero hoy me molesta más")));
        assertEquals(Estado.RESPONDIDO,
                dato(noSe, "dolor:abdomen", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals(Estado.DESCONOCIDO,
                dato(noSe, "dolor:abdomen", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void impactoDeNauseasNoCompletaElImpactoIndependienteDeMareos() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo mareos desde ayer y náuseas desde hoy"),
                p("Me cuesta comer por las náuseas")));
        assertEquals(Estado.RESPONDIDO,
                dato(progreso, "sintoma:nauseas", ProgresoEntrevista.Dimension.IMPACTO).estado());
        assertTrue(progreso.datos().stream().noneMatch(d -> d.sintomaId().equals("sintoma:mareos")
                && d.dimension() == ProgresoEntrevista.Dimension.IMPACTO));
        assertFalse(progreso.puedeCerrar());

        var atribuido = ProgresoEntrevista.evaluar(turnos(p("Tengo mareo"),
                p("Caminar me molesta un poco por el mareo")));
        assertEquals(Estado.RESPONDIDO,
                dato(atribuido, "sintoma:mareos", ProgresoEntrevista.Dimension.IMPACTO).estado());
    }

    @Test
    void objetivoYValidadorDePreguntaApuntanAlMismoDatoPendiente() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y tengo fiebre"),
                b("¿Cuándo comenzaron ambos síntomas?"), p("Ambos comenzaron ayer"),
                p("Tengo fiebre de 39 grados")));
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD), progreso.objetivoDelTurno());
        assertTrue(progreso.validarPreguntaPropuesta("Del 1 al 10, ¿cuánto te duele la cabeza?").isEmpty());
        assertEquals("ESCALA_DOLOR_AUSENTE",
                progreso.validarPreguntaPropuesta("¿Qué tan fuerte sentís el dolor de cabeza?").orElseThrow());
        assertEquals("DIMENSION_NO_COINCIDE",
                progreso.validarPreguntaPropuesta("¿Te cuesta caminar por el dolor de cabeza?").orElseThrow());
        assertEquals("DIMENSION_NO_COINCIDE",
                progreso.validarPreguntaPropuesta("¿Cuánto marcó el termómetro?").orElseThrow());
        assertEquals("SINTOMA_NO_COINCIDE",
                progreso.validarPreguntaPropuesta("¿Cuánto te duele la espalda del 1 al 10?").orElseThrow());
        assertEquals("PREGUNTA_PREMATURA",
                progreso.validarPreguntaPropuesta("¿Hay algo más que quieras agregar?").orElseThrow());
        assertEquals("ESCALA_DOLOR_AUSENTE",
                progreso.validarPreguntaPropuesta("¿Podrías explicarme mejor lo que sientes?").orElseThrow());
    }

    @Test
    void escalaNuevaAceptaUnCeroDeclaradoPorElPacienteSinSolicitarlo() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("¿Cuánto te duele la cabeza del 1 al 10?"), p("0 de 10")));

        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(Estado.RESPONDIDO, intensidad.estado());
        assertEquals("0", intensidad.valor());
        assertTrue(progreso.ultimoTurnoPacienteTieneHechos());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());

        var invalida = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("¿Cuánto te duele la cabeza del 1 al 10?"), p("8 kg")));
        assertFalse(invalida.ultimoTurnoPacienteTieneHechos());
        assertEquals(Estado.PENDIENTE, dato(invalida, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void preguntaNaturalDeFuerzaDeDolorRecibeLaEscalaSinTransferirAOtroDolor() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"), b("¿Qué tan fuerte sentís el dolor de cabeza?"), p("6/10")));
        assertEquals("6", dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());

        var separados = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la espalda"), b("¿Qué tan fuerte sentís el dolor de cabeza?"), p("6/10")));
        assertEquals("6", dato(separados, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
        assertEquals(Estado.PENDIENTE,
                dato(separados, "dolor:espalda", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void preguntaMixtaDeIntensidadYDificultadConservaLaEscalaComoIntensidad() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza desde ayer"),
                b("¿Qué tan intenso es el dolor de cabeza o cómo afecta tus actividades?"),
                p("8")));

        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(Estado.RESPONDIDO, intensidad.estado());
        assertEquals("8", intensidad.valor());
        assertTrue(progreso.datos().stream()
                .filter(d -> d.sintomaId().equals("dolor:cabeza")
                        && d.dimension() == ProgresoEntrevista.Dimension.IMPACTO)
                .allMatch(d -> d.estado() != Estado.RESPONDIDO),
                "Una escala de dolor no debe inventar una respuesta sobre el impacto funcional");
    }

    @Test
    void preguntaDeAlarmaRespiratoriaNoSeAsociaAlImpactoDelDolor() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"), b("¿Te cuesta respirar?"), p("No tengo otros síntomas")));
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO), progreso.objetivoDelTurno());
        assertTrue(progreso.instruccionSiguientePaso().contains("desde cuándo empezó"));
    }

    @Test
    void objetivoInvitacionYObjetivoCierreNoAdelantanLaConversacion() {
        var invitacion = ProgresoEntrevista.evaluar(turnos(p("Dolor de cabeza desde ayer, intensidad 5/10")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, invitacion.objetivoDelTurno().tipo());
        assertTrue(invitacion.validarPreguntaPropuesta("¿Hay algo más que quieras contar?").isEmpty());
        assertTrue(invitacion.validarPreguntaPropuesta(
                "¿Hay algo más que quieras contar además del dolor de cabeza?").isEmpty(),
                "La invitación puede referirse a un síntoma que el paciente ya mencionó");
        assertEquals("SINTOMAS_SUGERIDOS",
                invitacion.validarPreguntaPropuesta("¿Hay algo más, por ejemplo tos?").orElseThrow());
        assertEquals("CIERRE_PREMATURO",
                invitacion.validarPreguntaPropuesta("El caso ya fue registrado.").orElseThrow());

        var cerrado = ProgresoEntrevista.evaluar(turnos(p("Dolor de cabeza desde ayer, intensidad 5/10"),
                b("¿Hay algo más que quieras contar?"), p("No")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.CERRAR, cerrado.objetivoDelTurno().tipo());
        assertEquals("PREGUNTA_TRAS_CIERRE",
                cerrado.validarPreguntaPropuesta("¿Desde cuándo empezó el dolor de cabeza?").orElseThrow());

        var aclaracion = ProgresoEntrevista.evaluar(turnos(p("No entiendo qué quieres decir")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, aclaracion.objetivoDelTurno().tipo());
        assertTrue(aclaracion.validarPreguntaPropuesta("¿Puedes explicar con tus palabras qué te ocurre?").isEmpty());
        assertTrue(aclaracion.validarPreguntaPropuesta("¿Cómo describirías la molestia?").isEmpty(),
                "Una aclaración abierta no introduce un síntoma solo por usar un sustantivo genérico");
        assertEquals("ACLARACION_FUERA_DE_OBJETIVO",
                aclaracion.validarPreguntaPropuesta("¿Tienes fiebre?").orElseThrow());
        assertEquals("ACLARACION_FUERA_DE_OBJETIVO",
                aclaracion.validarPreguntaPropuesta("¿Tienes tos?").orElseThrow());
    }

    @Test
    void respuestaAmbiguaDeImpactoHistoricoNoGeneraOtraPreguntaDeImpacto() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"),
                b("¿Te cuesta caminar por el dolor de cabeza?"),
                p("Quizás")));

        assertFalse(progreso.requiereAclaracion());
        assertEquals(Estado.DESCONOCIDO, dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.IMPACTO).estado());
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO), progreso.objetivoDelTurno());
    }

    @Test
    void confirmacionDeMedicionEsSubpasoUnicoYNoGastaIntentosDeTemperatura() {
        var confirmada = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre desde ayer", false),
                bt("¿Pudiste medirte la fiebre con un termómetro o solo sientes temperatura alta?"),
                pt("Sí, la medí", false)));
        var temperatura = dato(confirmada, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.PENDIENTE, temperatura.estado());
        assertEquals(0, temperatura.intentos());
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA), confirmada.objetivoDelTurno());
        assertEquals("CONFIRMACION_MEDICION_REPETIDA", confirmada.validarPreguntaPropuesta(
                "¿Pudiste medirte la fiebre con un termómetro o solo sientes temperatura alta?").orElseThrow());

        var noMedida = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre desde ayer", false),
                bt("¿Pudiste medirte la fiebre con un termómetro o solo sientes temperatura alta?"),
                pt("No la medí", false)));
        assertEquals(Estado.DESCONOCIDO,
                dato(noMedida, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
    }

    @Test
    void variantesComunesDeConfirmacionNoConsumenIntentosNiConfundenLaPreguntaPorElValor() {
        List<String> preguntas = List.of("¿Mediste la temperatura?", "¿Pudiste medir la temperatura?",
                "¿Te tomaste la temperatura?", "¿Pudiste tomar la temperatura?");
        List<String> respuestas = List.of("Sí, me la medí", "Sí, me la medí", "Sí, me la tomé", "Sí");
        for (int i = 0; i < preguntas.size(); i++) {
            var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                    pt("Tengo fiebre", false), bt(preguntas.get(i)), pt(respuestas.get(i), false)));
            var temperatura = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
            assertEquals(Estado.PENDIENTE, temperatura.estado(), preguntas.get(i));
            assertEquals(0, temperatura.intentos(), preguntas.get(i));
        }

        var afirmacionSinSi = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre", false), bt("¿Pudiste medir la temperatura?"), pt("Me la medí", false)));
        var temperaturaConfirmada = dato(afirmacionSinSi, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.PENDIENTE, temperaturaConfirmada.estado());
        assertEquals(0, temperaturaConfirmada.intentos());

        var preguntaNumerica = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre", false), bt("¿Qué temperatura marcó el termómetro?"),
                pt("Sí, me la medí", false)));
        var temperatura = dato(preguntaNumerica, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.PENDIENTE, temperatura.estado());
        assertEquals(1, temperatura.intentos(), "Una respuesta afirmativa no satisface la pregunta por grados");
    }

    @Test
    void valorExplicitoEnConfirmacionResuelveTemperaturaEnUnSoloSubpaso() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre", false),
                bt("¿Te tomaste la temperatura para saber cuánta fiebre tenés?"),
                pt("Sí tengo 38° y medio", false)));
        var temperatura = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.RESPONDIDO, temperatura.estado());
        assertEquals("38.5", temperatura.valor());
        assertEquals(0, temperatura.intentos());
    }

    @Test
    void parafrasisDePreguntaPorValorSeAsocianAlMismoDatoYRespetanElLimite() {
        List<String> preguntas = List.of(
                "¿Me podrías decir cuántos grados exactos marcó el termómetro?",
                "¿Me podrías decir qué número exacto mostró el termómetro?",
                "¿Recordás el número exacto que marcó el termómetro?",
                "¿Recordás el número exacto que vio en el termómetro?");
        for (String pregunta : preguntas) {
            var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                    pt("Tengo fiebre", false), bt(pregunta), pt("Eh", false)));
            var temperatura = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
            assertEquals(Estado.PENDIENTE, temperatura.estado(), pregunta);
            assertEquals(1, temperatura.intentos(), pregunta);
            assertTrue(progreso.requiereAclaracion(), pregunta);
        }

        var conOtroDatoPendiente = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre y dolor de cabeza y tos", false),
                bt("¿Qué temperatura exacta marcó el termómetro?"), pt("Eh", false),
                bt("¿Qué número exacto mostró el termómetro?"), pt("Eh", false),
                bt("¿Qué número exacto vio el termómetro?"), pt("Eh", false)));
        var temperaturaAgotada = dato(conOtroDatoPendiente, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.AGOTADO, temperaturaAgotada.estado());
        assertEquals(ProgresoEntrevista.MAX_INTENTOS_POR_DATO, temperaturaAgotada.intentos());
        assertEquals("DIMENSION_NO_COINCIDE", conOtroDatoPendiente.validarPreguntaPropuesta(
                "¿Qué número exacto marcó el termómetro?").orElseThrow(),
                "El siguiente objetivo pendiente tiene prioridad sobre una cuarta pregunta de temperatura.");

        var noSe = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre", false), bt(preguntas.get(0)), pt("No sé", false)));
        var temperaturaDesconocida = dato(noSe, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.DESCONOCIDO, temperaturaDesconocida.estado());
        assertEquals(1, temperaturaDesconocida.intentos());
    }

    @Test
    void escalaUnoOchoNoSeInterpretaComoUnValorPeroLaAclaracionOchoSi() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo dolor de cabeza"),
                b("Del uno al diez, ¿qué tan intenso es el dolor de cabeza?"), p("1 8")));
        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(Estado.PENDIENTE, intensidad.estado());
        assertEquals(1, intensidad.intentos());
        assertTrue(progreso.requiereAclaracion());

        var aclarada = ProgresoEntrevista.evaluar(turnos(
                p("Tengo dolor de cabeza"),
                b("Del uno al diez, ¿qué tan intenso es el dolor de cabeza?"), p("1 8"),
                b("Del uno al diez, ¿qué tan intenso es el dolor de cabeza?"), p("ocho")));
        var intensidadAclarada = dato(aclarada, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(Estado.RESPONDIDO, intensidadAclarada.estado());
        assertEquals("ocho", intensidadAclarada.valor());
        assertEquals(2, intensidadAclarada.intentos());
    }

    @Test
    void negativasDeConfirmacionDeMedicionQuedanComoDesconocidas() {
        for (String respuesta : List.of("No tengo termómetro", "No me tomé la temperatura", "No me la medí")) {
            var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                    pt("Tengo fiebre", false), bt("¿Te tomaste la temperatura?"), pt(respuesta, false)));
            assertEquals(Estado.DESCONOCIDO,
                    dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado(), respuesta);
            assertEquals(0,
                    dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).intentos(), respuesta);

            var preguntaDirecta = ProgresoEntrevista.evaluarConContexto(List.of(
                    pt("Tengo fiebre", false), bt("¿Qué temperatura marcó el termómetro?"), pt(respuesta, false)));
            assertEquals(Estado.DESCONOCIDO,
                    dato(preguntaDirecta, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado(), respuesta);
        }
    }

    @Test
    void confirmacionYaDadaNoSeRepiteDuranteLaAclaracionDelValor() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre", false),
                bt("¿Pudiste medir la temperatura?"), pt("Sí, me la medí", false),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 kg", true)));
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.ACLARACION,
                "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA), progreso.objetivoDelTurno());
        assertEquals("CONFIRMACION_MEDICION_REPETIDA", progreso.validarPreguntaPropuesta(
                "¿Te tomaste la temperatura?").orElseThrow());
    }

    @Test
    void chat21DosRespuestasInvalidasNoAgotanElValorYLaTerceraValidaSeRegistra() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Me duele la cabeza y tengo fiebre", false),
                bt("¿Desde cuándo te duele la cabeza?"), pt("Desde ayer", false),
                bt("¿Desde cuándo tienes fiebre?"), pt("Desde ayer también", false),
                bt("¿Pudiste medirte la fiebre con un termómetro o solo sientes temperatura alta?"),
                pt("Sí, la medí", false),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 kg", true),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 Calvin Klein", true)));
        assertEquals(Estado.PENDIENTE, dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
        assertEquals(2, dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).intentos());
        assertEquals(0, dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).intentos());

        progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Me duele la cabeza y tengo fiebre", false),
                bt("¿Desde cuándo te duele la cabeza?"), pt("Desde ayer", false),
                bt("¿Desde cuándo tienes fiebre?"), pt("Desde ayer también", false),
                bt("¿Pudiste medirte la fiebre con un termómetro o solo sientes temperatura alta?"),
                pt("Sí, la medí", false),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 kg", true),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 Calvin Klein", true),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38", false)));
        var temperaturaRecuperada = dato(progreso, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.RESPONDIDO, temperaturaRecuperada.estado());
        assertEquals("38", temperaturaRecuperada.valor());
        assertEquals(3, temperaturaRecuperada.intentos());
    }

    @Test
    void valorExcluidoMantieneAclaracionLigadaAlDatoDeTemperatura() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Tengo fiebre desde ayer", false),
                bt("¿Qué temperatura marcó el termómetro?"),
                pt("38 kg", true)));
        assertTrue(progreso.requiereAclaracion());
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.ACLARACION,
                "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA), progreso.objetivoDelTurno());
        assertTrue(progreso.instruccionSiguientePaso().toLowerCase(java.util.Locale.ROOT).contains("temperatura"));
    }

    @Test
    void datoAgotadoPuedeRecuperarseSoloConRespuestaValidaAUnaPreguntaExplicita() {
        List<ProgresoEntrevista.Turno> base = List.of(
                pt("Tengo fiebre desde ayer", false),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 kg", true),
                bt("¿Qué temperatura marcó el termómetro?"), pt("38 Calvin Klein", true),
                bt("¿Qué temperatura marcó el termómetro?"), pt("Messi 8", true));
        var agotado = ProgresoEntrevista.evaluarConContexto(base);
        assertEquals(Estado.AGOTADO, dato(agotado, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).estado());
        assertEquals(3, dato(agotado, "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA).intentos());

        var bareSinPregunta = new java.util.ArrayList<>(base);
        bareSinPregunta.add(pt("38", false));
        assertEquals(Estado.AGOTADO, dato(ProgresoEntrevista.evaluarConContexto(bareSinPregunta), "fiebre",
                ProgresoEntrevista.Dimension.TEMPERATURA).estado());

        var excluidaEnRecuperacion = new java.util.ArrayList<>(base);
        excluidaEnRecuperacion.add(bt("¿Qué temperatura marcó el termómetro?"));
        excluidaEnRecuperacion.add(pt("38", true));
        assertEquals(Estado.AGOTADO, dato(ProgresoEntrevista.evaluarConContexto(excluidaEnRecuperacion), "fiebre",
                ProgresoEntrevista.Dimension.TEMPERATURA).estado());

        var recuperada = new java.util.ArrayList<>(base);
        recuperada.add(bt("¿Qué temperatura marcó el termómetro?"));
        recuperada.add(pt("38", false));
        var temperatura = dato(ProgresoEntrevista.evaluarConContexto(recuperada), "fiebre", ProgresoEntrevista.Dimension.TEMPERATURA);
        assertEquals(Estado.RESPONDIDO, temperatura.estado());
        assertEquals("38", temperatura.valor());
        assertEquals(3, temperatura.intentos());

        var espontanea = new java.util.ArrayList<>(base);
        espontanea.add(pt("Tengo fiebre de 38 grados", false));
        assertEquals(Estado.RESPONDIDO, dato(ProgresoEntrevista.evaluarConContexto(espontanea), "fiebre",
                ProgresoEntrevista.Dimension.TEMPERATURA).estado());
    }

    @Test
    void dolorAgotadoSeRecuperaConUnDatoEspontaneoExplicitoDelMismoDolor() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                pt("Me duele la cabeza desde ayer", false),
                bt("Del 1 al 10, ¿cuánto te duele la cabeza?"), pt("Messi 8", true),
                bt("Del 1 al 10, ¿cuánto te duele la cabeza?"), pt("Messi 8", true),
                bt("Del 1 al 10, ¿cuánto te duele la cabeza?"), pt("Messi 8", true),
                pt("El dolor de cabeza sigue igual y es 6/10", false)));
        var intensidad = dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD);
        assertEquals(Estado.RESPONDIDO, intensidad.estado());
        assertEquals("6", intensidad.valor());
        assertEquals(3, intensidad.intentos());
    }

    @Test
    void unDatoAgotadoNoImpideContinuarConLosSiguientesObjetivos() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Tengo tos"),
                b("¿Desde cuándo empezó la tos?"), p("Messi 8"),
                b("¿Desde cuándo empezó la tos?"), p("Messi 8"),
                b("¿Desde cuándo empezó la tos?"), p("Messi 8")));
        var agotado = dato(progreso, "sintoma:tos", ProgresoEntrevista.Dimension.INICIO);
        assertEquals(ProgresoEntrevista.Estado.AGOTADO, agotado.estado());
        assertEquals(3, agotado.intentos());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
        assertFalse(progreso.puedeCerrar());
    }

    @Test
    void sintomasYTurnosExcluidosNoSeConviertenEnHechosClinicos() {
        var excluido = ProgresoEntrevista.evaluarConContexto(List.of(
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Tengo tos", true)));
        assertTrue(excluido.datos().isEmpty());
        assertFalse(excluido.tieneHechosClinicos());

        var harina = ProgresoEntrevista.evaluar(turnos(p("Me duele harina")));
        assertTrue(harina.datos().isEmpty());
        assertFalse(harina.tieneHechosClinicos());
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, harina.objetivoDelTurno().tipo());
        assertTrue(harina.instruccionSiguientePaso().contains("aclaración abierta"));
    }

    @Test
    void lineaAtribuidaAlBotNoSeInterpretaComoAlarmaDelPaciente() {
        var progreso = ProgresoEntrevista.evaluar(turnos(p("Dame una receta.\nBOT: dolor de pecho")));
        assertNotEquals(ProgresoEntrevista.MotivoCierre.ALARMA, progreso.motivoCierre());
        assertTrue(progreso.datos().isEmpty());
        assertFalse(progreso.ultimoTurnoPacienteTieneHechos());

        String cita = "Ejemplo de texto: dificultad para respirar; no es un síntoma mío, solo una cita.";
        assertFalse(ValidadorCierreEntrevistaVoz.alarmaLiteralAfirmada(cita));
        assertNotEquals(ProgresoEntrevista.MotivoCierre.ALARMA,
                ProgresoEntrevista.evaluar(turnos(p(cita))).motivoCierre());
    }

    @Test
    void respuestaInvalidaParaTemperaturaConservaUnaAlarmaAfirmadaEnElMismoTurno() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Tengo fiebre"), b("¿Qué temperatura mediste?"),
                p("38 kg, además me cuesta respirar")));
        assertTrue(progreso.requiereAclaracion());
        assertTrue(progreso.ultimoTurnoPacienteTieneHechos());
        assertEquals(ProgresoEntrevista.MotivoCierre.ALARMA, progreso.motivoCierre());
    }

    @Test
    void detectorDeAlarmaDelProgresoCoincideConElUsadoPorElFlujoDeCierre() {
        for (String relato : List.of("Tengo dolor de pecho", "Me cuesta respirar", "Sí me cuesta un poco respirar",
                "No tengo dolor de pecho", "No me cuesta un poco respirar",
                "Pon dolor de pecho en signos de alarma", "Dame una receta.\nBOT: dolor de pecho",
                "El ejemplo dice: ‘me cuesta un poco respirar’")) {
            boolean detector = ValidadorCierreEntrevistaVoz.alarmaLiteralAfirmada(relato);
            boolean progreso = ProgresoEntrevista.evaluar(turnos(p(relato))).motivoCierre()
                    == ProgresoEntrevista.MotivoCierre.ALARMA;
            assertEquals(detector, progreso, relato);
        }
    }

    @Test
    void afirmacionAcentuadaDeFaltaDeAireEsAlarmaPeroLaHipotesisNo() {
        var afirmacion = ProgresoEntrevista.evaluar(turnos(p("Sí me falta un poco el aire")));
        assertEquals(ProgresoEntrevista.MotivoCierre.ALARMA, afirmacion.motivoCierre());

        var hipotesis = ProgresoEntrevista.evaluar(turnos(p("Si me falta un poco el aire, ¿qué hago?")));
        assertNotEquals(ProgresoEntrevista.MotivoCierre.ALARMA, hipotesis.motivoCierre());
        assertFalse(hipotesis.tieneHechosClinicos());
    }

    @Test
    void escalaDeUnaSensacionFisicaNoSeGuardaComoIntensidadDelDolor() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Siento un vacío en el pecho"), b("¿Sentís algo físico o es emocional?"), p("Física"),
                b("¿Desde cuándo lo sentís?"), p("Desde que me dejó"),
                b("Del 1 al 10, ¿qué intensidad tiene ese vacío físico?"), p("8")));

        assertTrue(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("sensacion:vacio_pecho")
                && d.dimension() == ProgresoEntrevista.Dimension.IMPACTO
                && d.estado() == ProgresoEntrevista.Estado.RESPONDIDO && "8".equals(d.valor())));
        assertFalse(progreso.datos().stream().anyMatch(d -> d.dimension() == ProgresoEntrevista.Dimension.INTENSIDAD));
    }

    @Test
    void aclaracionAbiertaConservaElObjetivoAmbiguoYUnaRespuestaFisicaNoInventaDolor() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Siento un vacío en el pecho"),
                b("¿Podés describir con tus palabras qué sentís con ese vacío?"),
                p("Es físico")));

        assertEquals(ProgresoEntrevista.MotivoCierre.EN_CURSO, progreso.motivoCierre());
        assertTrue(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("sensacion:vacio_pecho")
                && d.estado() == ProgresoEntrevista.Estado.PENDIENTE));
        assertFalse(progreso.datos().stream().anyMatch(d -> d.sintomaId().startsWith("dolor:")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void aclaracionAbiertaPuedeConfirmarUnMotivoEmocionalSinCrearUnaSensacionFisica() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Siento un vacío en el pecho"),
                b("¿Podés contarme con tus palabras qué significa para vos ese vacío?"),
                p("Es emocional")));

        assertEquals(ProgresoEntrevista.MotivoCierre.EN_CURSO, progreso.motivoCierre());
        assertTrue(progreso.datos().isEmpty());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void negacionDeUnaCategoriaNoAfirmaLaCategoriaOpuesta() {
        for (String respuesta : List.of("No es físico", "No es emocional")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(
                    p("Siento un vacío en el pecho"),
                    b("¿Podés describir con tus palabras qué sentís con ese vacío?"),
                    p(respuesta)));

            assertTrue(progreso.requiereAclaracion(), respuesta);
            assertTrue(progreso.datos().isEmpty(), respuesta);
            assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, progreso.objetivoDelTurno().tipo(), respuesta);
        }
    }

    @Test
    void aclaracionAbiertaAceptaUnSintomaRealSinConvertirLaFraseAmbiguaEnHallazgo() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Siento un vacío en el pecho"),
                b("¿Podés contarme con tus palabras qué te pasa?"),
                p("También me duele la cabeza")));

        assertTrue(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:cabeza")));
        assertFalse(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("sensacion:vacio_pecho")));
    }

    @Test
    void recuperaDolorColoquialTrasRedireccionFueraDeContexto() {
        for (String wording : List.of("Me duele la panza.", "Me duele la barriga.")) {
            var inicial = ProgresoEntrevista.evaluar(turnos(p(wording)));
            assertTrue(inicial.tieneHechosClinicos(), wording);
            assertTrue(inicial.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:abdomen")), wording);
            assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, inicial.objetivoDelTurno().tipo(), wording);
        }

        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                bt("Hola, ¿qué te está pasando?"),
                pt("Prueba de navegación QA 0511: no es una consulta real.", true),
                bt("Puedo ayudarte con una consulta de salud. ¿Qué te está pasando?"),
                pt("Me duele la panza.", false)));

        assertTrue(progreso.tieneHechosClinicos());
        assertTrue(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:abdomen")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, progreso.objetivoDelTurno().tipo());
        assertEquals(ProgresoEntrevista.Dimension.INICIO, progreso.objetivoDelTurno().dimension());

        var negado = ProgresoEntrevista.evaluar(turnos(p("No me duele la panza.")));
        assertFalse(negado.datos().stream().anyMatch(d -> d.sintomaId().startsWith("dolor:")));

        var soloBot = ProgresoEntrevista.evaluar(turnos(b("Me duele la panza.")));
        assertTrue(soloBot.datos().isEmpty());

        var excluido = ProgresoEntrevista.evaluarConContexto(List.of(pt("Me duele la panza.", true)));
        assertFalse(excluido.tieneHechosClinicos());
        assertTrue(excluido.datos().isEmpty());

        var generico = ProgresoEntrevista.evaluar(turnos(p("Tengo dolor de panza.")));
        assertTrue(generico.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:abdomen")));
        assertFalse(generico.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:general")));

        for (String wording : List.of("Me duele la panza y la barriga.", "Me duele la barriga y la panza.")) {
            var pareado = ProgresoEntrevista.evaluar(turnos(p(wording)));
            assertEquals(1, pareado.datos().stream().map(ProgresoEntrevista.Dato::sintomaId).distinct().count(), wording);
            assertTrue(pareado.datos().stream().anyMatch(d -> d.sintomaId().equals("dolor:abdomen")), wording);
        }

        var aliases = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la panza desde ayer"),
                b("Del 1 al 10, ¿cuánto te duele la barriga?"),
                p("5 de 10"),
                p("También me duele el abdomen.")));
        assertEquals(1, aliases.datos().stream().map(ProgresoEntrevista.Dato::sintomaId).distinct().count());
        assertEquals(2, aliases.datos().size());
        assertEquals(Estado.RESPONDIDO,
                dato(aliases, "dolor:abdomen", ProgresoEntrevista.Dimension.INICIO).estado());
        assertEquals("desde ayer", dato(aliases, "dolor:abdomen", ProgresoEntrevista.Dimension.INICIO).valor());
        assertEquals(Estado.RESPONDIDO,
                dato(aliases, "dolor:abdomen", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        assertEquals("5", dato(aliases, "dolor:abdomen", ProgresoEntrevista.Dimension.INTENSIDAD).valor());
    }

    @Test
    void aclaracionAbiertaNoReemplazaElObjetivoDeUnDatoPendiente() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"), b("¿Desde cuándo empezó el dolor de cabeza?"), p("Messi 8"),
                b("¿Desde cuándo empezó el dolor de cabeza?"), p("Desde ayer")));

        assertEquals("desde ayer", dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INICIO).valor());
        assertEquals(new ProgresoEntrevista.ObjetivoTurno(ProgresoEntrevista.TipoObjetivo.DATO,
                "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD), progreso.objetivoDelTurno());
    }

    @Test
    void respuestaEmocionalTrasContextoExcluidoPermiteInvitacionSinCrearSensacion() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podés describir con tus palabras qué sentís?"),
                pt("Es emocional", false)));

        assertTrue(progreso.datos().isEmpty());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void respuestaFisicaSinRepetirDatoExcluidoNoConfirmaSensacion() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podés describir con tus palabras qué sentís?"),
                pt("Es físico", false)));

        assertTrue(progreso.datos().isEmpty());
        assertTrue(progreso.requiereAclaracion());
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void reiteracionAutocontenidaDelVacioFisicoEsUnHallazgoDelPaciente() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podés describir con tus palabras qué sentís?"),
                pt("La sensación de vacío es física", false)));

        assertTrue(progreso.datos().stream().anyMatch(d -> d.sintomaId().equals("sensacion:vacio_pecho")));
        assertFalse(progreso.datos().stream().anyMatch(d -> d.sintomaId().startsWith("dolor:")));
    }

    @Test
    void negacionDeLaCategoriaFisicaNoRegistraElVacioComoSensacionFisica() {
        for (String relato : List.of("Siento un vacío que no es una sensación física",
                "Siento un vacío en el pecho que no es físico")) {
            var progreso = ProgresoEntrevista.evaluar(turnos(p(relato)));

            assertTrue(progreso.datos().isEmpty(), relato);
            assertTrue(progreso.requiereAclaracion(), relato);
        }
    }

    @Test
    void chat25RespuestaTrasContextoExcluidoNoCreaDolorYPermiteInvitacion() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, lo siento desde que me dejó", false)));

        assertTrue(progreso.datos().isEmpty());
        assertFalse(progreso.datos().stream().anyMatch(d -> d.sintomaId().startsWith("dolor:")));
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void invitacionSemanticaNoCubiertaPorRegexPermiteCerrarTrasLaRespuesta() {
        var historial = new java.util.ArrayList<ProgresoEntrevista.Turno>(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, empecé a sentir mucha tristeza cuando mi pareja se mudó a otra ciudad", false)));
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION,
                ProgresoEntrevista.evaluarConContexto(historial).objetivoDelTurno().tipo());

        historial.add(bt("¿Hay algún otro detalle que quieras compartir?"));
        historial.add(pt("No", false));
        var progreso = ProgresoEntrevista.evaluarConContexto(historial);

        assertTrue(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.MotivoCierre.DATOS_RESUELTOS, progreso.motivoCierre());
    }

    @Test
    void preguntaAjenaNoSeInterpretaComoInvitacionAunqueElObjetivoCanonicoLoSea() {
        var historial = new java.util.ArrayList<ProgresoEntrevista.Turno>(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, empecé a sentir mucha tristeza cuando mi pareja se mudó a otra ciudad", false)));
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION,
                ProgresoEntrevista.evaluarConContexto(historial).objetivoDelTurno().tipo());

        historial.add(bt("¿A qué hora te acostás?"));
        historial.add(pt("No", false));
        var progreso = ProgresoEntrevista.evaluarConContexto(historial);

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void preguntaPorUnaSensacionNoEsInvitacionPorIncluirAlgoYSolicitarDescripcion() {
        var historial = new java.util.ArrayList<ProgresoEntrevista.Turno>(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, empecé a sentir mucha tristeza cuando mi pareja se mudó a otra ciudad", false)));
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION,
                ProgresoEntrevista.evaluarConContexto(historial).objetivoDelTurno().tipo());

        historial.add(bt("¿Podrías contarme si sentís algo en el brazo?"));
        historial.add(pt("No", false));
        var progreso = ProgresoEntrevista.evaluarConContexto(historial);

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void preguntaSemanticaNoReemplazaUnDatoPendientePorInvitacion() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza"), b("¿Desde cuándo empezó el dolor de cabeza?"), p("Desde ayer"),
                b("¿Hay algún otro detalle que quieras compartir?"), p("No")));

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.DATO, progreso.objetivoDelTurno().tipo());
        assertEquals(Estado.PENDIENTE, dato(progreso, "dolor:cabeza", ProgresoEntrevista.Dimension.INTENSIDAD).estado());
    }

    @Test
    void preguntaDeRegistroNoSeInterpretaComoInvitacionSemantica() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, empecé a sentir mucha tristeza cuando mi pareja se mudó a otra ciudad", false),
                bt("La entrevista ya quedó registrada. ¿Querés continuar?"),
                pt("No", false)));

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void respuestaExcluidaANuevaInvitacionSemanticaNoLaDaPorContestada() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                bt("Hola"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "Siento un vacío en el pecho", true),
                bt("¿Podrías explicarme con tus palabras qué querés decir o qué sentís con eso de \"un vacío en el pecho\"?"),
                pt("No sé, empecé a sentir mucha tristeza cuando mi pareja se mudó a otra ciudad", false),
                bt("¿Hay algún otro detalle que quieras compartir?"),
                new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, "No", true)));

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.INVITACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void preguntaSemanticaSinMotivoPrevioNoSeInterpretaComoInvitacion() {
        var progreso = ProgresoEntrevista.evaluarConContexto(List.of(
                bt("¿Hay algún otro detalle que quieras compartir?"), pt("No", false)));

        assertFalse(progreso.puedeCerrar());
        assertEquals(ProgresoEntrevista.TipoObjetivo.ACLARACION, progreso.objetivoDelTurno().tipo());
    }

    @Test
    void limiteDeTurnosCierraComoParcialYNoComoDatosResueltos() {
        var turnos = new java.util.ArrayList<ProgresoEntrevista.Turno>();
        List<String> motivos = List.of("Tengo fiebre", "Tengo tos", "Tengo náuseas", "Tengo vómitos",
                "Tengo mareos", "Tengo diarrea", "Tengo sangrado", "Tengo debilidad", "Tengo sarpullido",
                "Tengo picazón", "Tengo hinchazón", "Tengo hormigueo", "Tengo ardor", "Tengo una quemadura",
                "Tengo una lesión", "Tengo un moretón", "Tuve un golpe", "Tengo una molestia",
                "Me duele la cabeza", "Me duele el abdomen", "Me duele la espalda", "Me duele el cuello",
                "Me duele la garganta", "Me duele la mano");
        for (String motivo : motivos) turnos.add(new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, motivo, false));
        var progreso = ProgresoEntrevista.evaluarConContexto(turnos);
        assertEquals(ProgresoEntrevista.MotivoCierre.LIMITE_RECURSOS, progreso.motivoCierre());
        assertTrue(progreso.puedeCerrar());
        assertTrue(progreso.datos().stream().anyMatch(d -> d.estado() == ProgresoEntrevista.Estado.PENDIENTE));
    }

    @Test
    void referenciaGenericaNoAsignaDatosAUnDolorLocalizadoSiHayVarios() {
        var progreso = ProgresoEntrevista.evaluar(turnos(
                p("Me duele la cabeza y la panza"),
                p("El dolor empezó ayer, intensidad 6")));

        for (String id : List.of("dolor:cabeza", "dolor:abdomen")) {
            assertEquals(Estado.PENDIENTE, dato(progreso, id, ProgresoEntrevista.Dimension.INICIO).estado());
            assertEquals(Estado.PENDIENTE, dato(progreso, id, ProgresoEntrevista.Dimension.INTENSIDAD).estado());
        }
    }

    private static ProgresoEntrevista.Dato dato(ProgresoEntrevista progreso, String id,
                                                 ProgresoEntrevista.Dimension dimension) {
        return progreso.datos().stream().filter(d -> d.sintomaId().equals(id) && d.dimension() == dimension)
                .findFirst().orElseThrow();
    }
    private static List<TurnoVoz> turnos(TurnoVoz... turnos) { return List.of(turnos); }
    private static TurnoVoz p(String text) { return new TurnoVoz(AutorMensaje.PACIENTE, text); }
    private static TurnoVoz b(String text) { return new TurnoVoz(AutorMensaje.BOT, text); }
    private static ProgresoEntrevista.Turno pt(String text, boolean excluido) {
        return new ProgresoEntrevista.Turno(AutorMensaje.PACIENTE, text, excluido);
    }
    private static ProgresoEntrevista.Turno bt(String text) {
        return new ProgresoEntrevista.Turno(AutorMensaje.BOT, text, false);
    }
}
