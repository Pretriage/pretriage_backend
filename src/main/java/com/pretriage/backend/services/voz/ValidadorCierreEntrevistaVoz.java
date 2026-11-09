package com.pretriage.backend.services.voz;

import com.pretriage.backend.controllers.dtos.ResumenEntrevistaVoz;
import com.pretriage.backend.controllers.dtos.TurnoVoz;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.services.entrevista.ProgresoEntrevista;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Checks closure eligibility without trusting Gemini's summary as patient testimony. */
public final class ValidadorCierreEntrevistaVoz {
    private static final String SINTOMAS = "dolor|duele|duelen|cefalea|molestia|fiebre|tos|vomitos?|nauseas?|mareos?|sangrado|diarrea|debilidad|moraton(?:es)?|moreton(?:es)?|hematomas?|golpes?|hinchazon|hormigueo|entumecimiento|heridas?|lesion(?:es)?|quemaduras?|queme|quemado|quemada|ardor|me arde|cortes?|cortado|cortada|picazon|me pica|ronchas?|erupcion|sarpullido|torcedura|torci|lastimado|lastimada";
    private static final String ALARMAS = "no puedo respirar|me cuesta respirar|dificultad respiratoria|dificultad para respirar|falta de aire|no puedo mover (?:el|un) brazo|boca (?:torcida|desviada)|debilidad unilateral|debilidad (?:subita|repentina)|me duele el pecho|dolor (?:de|en el) pecho|opresion (?:toracica|en el pecho)|disnea|perdida de (?:conciencia|conocimiento)|desmayos?|confusion|desorientad[oa]s?|convulsion(?:es)?|sangrado abundante|reaccion alergica";
    private static final Pattern INTENSIDAD = Pattern.compile("\\b(10|[0-9])\\s*(?:/|de|sobre)\\s*10\\b");
    private static final Pattern NEGACION = Pattern.compile("\\b(?:no|sin|niega|niego|descarta|descarto|ningun|ninguna|ninguno)\\b");
    private static final Pattern AFIRMACION_TRAS_NEGACION = Pattern.compile("\\b(?:pero|aunque|si|y)\\s+(?:me|tengo|siento|presento|aparecio|aparecen|noto)\\b");
    private static final Pattern FALTA_AIRE_COLOQUIAL = Pattern.compile(
            "\\bfalta\\s+(?:(?:un\\s+)?(?:poco|poquito|algo|bastante|mucho|muchisimo)\\s+)?(?:(?:el|de)\\s+)?aire\\b");
    private static final Pattern CUESTA_RESPIRAR_COLOQUIAL = Pattern.compile(
            "\\bme\\s+cuesta\\s+(?:(?:un\\s+)?(?:poco|poquito|algo|bastante|mucho|muchisimo)\\s+)?(?:el\\s+)?respirar(?:\\s+(?:un\\s+)?(?:poco|poquito|algo|bastante|mucho|muchisimo))?\\b");
    private static final Pattern TEXTO_CITADO = Pattern.compile("(?s)(\"[^\"]*\"|'[^']*'|“[^”]*”|«[^»]*»|‘[^’]*’|`[^`]*`)");
    private static final Pattern CITA_ALARMA = Pattern.compile(
            "^(?:ejemplo(?: de texto)?|cita(?: textual)?|texto citado|(?:el paciente|el bot|alguien) dijo|(?:el|mi) (?:doctor|medico) dijo|me dijeron)\\b.*");
    private static final Pattern HIPOTESIS_ALARMA = Pattern.compile(
            "\\b(?:si|en\\s+caso(?:\\s+de(?:\\s+que)?)?|supongamos(?:\\s+que)?)\\b[^,;.!?]{0,60}$");
    private static final Pattern AFIRMACION_SI = Pattern.compile("(?iuU)\\bsí\\b");

    private ValidadorCierreEntrevistaVoz() { }

    /** Empty means closure may proceed; otherwise return a safe corrective tool response. */
    public static Optional<String> validar(List<TurnoVoz> historial, ResumenEntrevistaVoz resumen) {
        return validarConProgreso(ProgresoEntrevista.evaluar(historial), resumen);
    }

    /** Validates closure against a context snapshot while preserving turns excluded from clinical evidence. */
    public static Optional<String> validarConContexto(List<ProgresoEntrevista.Turno> historial,
                                                       ResumenEntrevistaVoz resumen) {
        return validarConProgreso(ProgresoEntrevista.evaluarConContexto(historial), resumen);
    }

    private static Optional<String> validarConProgreso(ProgresoEntrevista progreso, ResumenEntrevistaVoz resumen) {
        if (!estructuraValida(resumen)) {
            return Optional.of("Corrige el resumen: incluye todos los campos de texto y listas requeridos, "
                    + "sin textos vacios; usa no informado o listas vacias para datos ausentes y dolor entre 0 y 10 o null. No inventes datos.");
        }
        return progreso.puedeCerrar() ? Optional.empty() : Optional.of(progreso.instruccionSiguientePaso());
    }

    /** Text and voice use the same patient evidence, including answers to preceding questions. */
    public static boolean tieneDatosSuficientes(List<TurnoVoz> historial) {
        return ProgresoEntrevista.evaluar(historial).puedeCerrar();
    }

    private static boolean sintomaAfirmado(String texto, String sintomas) {
        for (String segmento : texto.split("[,;.!?\\n]|\\bpero\\b|\\bsin embargo\\b|\\baunque\\b")) {
            var sintoma = Pattern.compile("\\b(?:" + sintomas + ")\\b").matcher(segmento);
            while (sintoma.find()) {
                String prefijo = segmento.substring(0, sintoma.start());
                var negacion = NEGACION.matcher(prefijo);
                int ultimaNegacion = -1;
                while (negacion.find()) ultimaNegacion = negacion.start();
                if (ultimaNegacion < 0 || AFIRMACION_TRAS_NEGACION.matcher(prefijo.substring(ultimaNegacion)).find()) return true;
            }
        }
        return false;
    }

    public static boolean tieneMotivoConcreto(List<TurnoVoz> turnos) {
        if (turnos == null) return false;
        for (TurnoVoz turno : turnos) {
            if (turno == null || turno.contenido() == null) continue;
            String texto = normalizar(turno.contenido());
            if (!esRespuesta(turno)) continue;
            for (String segmento : texto.split("[,;.!?\\n]|\\bpero\\b|\\bsin embargo\\b|\\baunque\\b")) {
                if (tieneSintomaAfirmado(segmento)) return true;
            }
        }
        return tieneVacioFisicoConfirmado(turnos);
    }

    /**
     * A patient's explicit physical clarification can disambiguate a vacuum sensation
     * they already described. BOT language alone never creates that symptom.
     */
    private static boolean tieneVacioFisicoConfirmado(List<TurnoVoz> turnos) {
        if (turnos == null) return false;
        boolean aclaracionPendiente = false;
        boolean tieneAclaracion = false;
        boolean fisico = false;
        boolean relatoVacioAfirmado = false;
        for (TurnoVoz turno : turnos) {
            if (turno == null || turno.contenido() == null) continue;
            String texto = normalizar(turno.contenido());
            if (turno.autor() == AutorMensaje.BOT) {
                aclaracionPendiente = contiene(texto, "fisic[oa]|corporal")
                        && contiene(texto, "emocional|sentis|sientes|sensacion");
                continue;
            }
            if (!esRespuesta(turno)) continue;
            if (aclaracionPendiente) {
                tieneAclaracion = true;
                fisico = respuestaAclaraFisico(texto);
                aclaracionPendiente = false;
            }
            for (String segmento : texto.split("[,;.!?\\n]|\\bpero\\b|\\bsin embargo\\b|\\baunque\\b")) {
                relatoVacioAfirmado |= tieneSintomaAfirmado(segmento, "vacio|vacia|vacios|vacias")
                        && contiene(segmento, "pecho|cuerpo");
            }
        }
        return tieneAclaracion && fisico && relatoVacioAfirmado;
    }

    private static boolean respuestaAclaraFisico(String respuesta) {
        return sintomaAfirmado(respuesta, "fisic[oa]|corporal|en el cuerpo|del cuerpo");
    }

    private static boolean tieneSintomaAfirmado(String segmento) {
        return tieneSintomaAfirmado(segmento, SINTOMAS);
    }

    private static boolean tieneSintomaAfirmado(String segmento, String sintomas) {
        var sintoma = Pattern.compile("\\b(?:" + sintomas + ")\\b").matcher(segmento);
        while (sintoma.find()) {
            String prefijo = segmento.substring(0, sintoma.start());
            var negacion = NEGACION.matcher(prefijo);
            int ultimaNegacion = -1;
            while (negacion.find()) ultimaNegacion = negacion.start();
            if (ultimaNegacion < 0 || AFIRMACION_TRAS_NEGACION.matcher(prefijo.substring(ultimaNegacion)).find()) return true;
        }
        return false;
    }

    public static boolean tieneInvitacionAbierta(List<TurnoVoz> turnos) {
        return ultimaInvitacionAbierta(turnos) >= 0;
    }

    public static boolean rechazoGlobalExplicito(List<TurnoVoz> turnos) {
        if (turnos == null) return false;
        boolean invitacionAbierta = false;
        boolean rechazoGlobal = false;
        for (TurnoVoz turno : turnos) {
            if (turno == null || turno.contenido() == null) continue;
            String texto = normalizar(turno.contenido());
            if (turno.autor() == AutorMensaje.BOT) {
                invitacionAbierta = esInvitacionAbierta(texto);
            } else if (esRespuesta(turno)) {
                rechazoGlobal |= (invitacionAbierta && esNegacionBreve(texto))
                        || contiene(texto, "no tengo (?:nada mas|mas sintomas|otros sintomas)|no hay (?:nada mas|otros sintomas)|nada mas que agregar|no quiero (?:agregar|seguir|contar)|no deseo (?:agregar|seguir|contar)");
                invitacionAbierta = false;
            }
        }
        return rechazoGlobal;
    }

    private static int ultimaInvitacionAbierta(List<TurnoVoz> turnos) {
        int ultima = -1;
        if (turnos == null) return ultima;
        for (int i = 0; i < turnos.size(); i++) {
            TurnoVoz turno = turnos.get(i);
            if (turno != null && turno.autor() == AutorMensaje.BOT && turno.contenido() != null
                    && esInvitacionAbierta(normalizar(turno.contenido()))) ultima = i;
        }
        return ultima;
    }

    private static boolean esInvitacionAbierta(String texto) {
        return contiene(texto, "algo mas|otra cosa|algo para agregar|algo que quieras (?:contar|agregar)|queres agregar algo|quieres agregar algo|(?:tenes|tienes) algun otro sintoma ademas|hay algun otro sintoma ademas");
    }

    public static String instruccionDatosPendientes(List<TurnoVoz> turnos) {
        return ProgresoEntrevista.evaluar(turnos).instruccionSiguientePaso();
    }

    private static boolean estructuraValida(ResumenEntrevistaVoz r) {
        return r != null && textoValido(r.motivoConsulta()) && textoValido(r.inicio())
                && textoValido(r.evolucion()) && textoValido(r.posibilidadEmbarazo()) && textoValido(r.observaciones())
                && listaValida(r.sintomas()) && listaValida(r.signosAlarma()) && listaValida(r.antecedentesRelevantes())
                && listaValida(r.medicamentos()) && listaValida(r.alergias())
                && (r.intensidadDolor() == null || (r.intensidadDolor() >= 0 && r.intensidadDolor() <= 10));
    }

    /** Reuses the same alarm filtering when the caller builds a conservative fallback. */
    public static List<String> signosAlarmaSignificativos(ResumenEntrevistaVoz resumen) {
        if (resumen == null || resumen.signosAlarma() == null) return List.of();
        return signosAlarmaAfirmados(resumen.signosAlarma());
    }

    public static List<String> signosAlarmaAfirmados(List<String> signos) {
        if (signos == null) return List.of();
        return signos.stream().filter(ValidadorCierreEntrevistaVoz::textoValido)
                .filter(ValidadorCierreEntrevistaVoz::alarmaResumenSignificativa).toList();
    }

    /** The same literal evidence must authorize early closure and guard its classification. */
    public static boolean contieneAlarmaLiteral(List<TurnoVoz> historial) {
        return historial != null && historial.stream().filter(ValidadorCierreEntrevistaVoz::esRespuesta)
                .anyMatch(turno -> alarmaLiteralAfirmada(turno.contenido()));
    }

    /** Primitive shared with the history projection so closure and priority use identical alarm evidence. */
    public static boolean alarmaLiteralAfirmada(String texto) {
        if (texto == null) return false;
        String evidencia = java.util.Arrays.stream(texto.split("\\R"))
                .filter(linea -> !normalizar(linea)
                        .matches("^\\s*(?:bot|assistant|system|developer|gemini|chatgpt)\\s*[:：].*$"))
                .map(ValidadorCierreEntrevistaVoz::normalizarConSiAfirmativo)
                .reduce((a, b) -> a + "\\n" + b).orElse("");
        evidencia = evidencia.replaceAll("\\bejemplo de texto\\s*[:：-]?\\s*[^;\\n.]+;?\\s*no es un sintoma mio[^.\\n]*solo una cita[^.\\n]*", "");
        return alarmaAfirmada(evidencia);
    }

    /** Latest explicit pain rating, including a numeric answer to the preceding scale question. */
    public static Integer ultimaIntensidadLiteral(List<TurnoVoz> historial) {
        if (historial == null) return null;
        Integer ultima = null;
        String pregunta = "";
        for (TurnoVoz turno : historial) {
            if (turno == null || turno.contenido() == null) continue;
            String texto = normalizar(turno.contenido());
            if (turno.autor() == AutorMensaje.BOT) {
                pregunta = texto;
            } else if (esRespuesta(turno)) {
                Integer intensidad = extraerIntensidad(texto, pregunta);
                if (intensidad != null) ultima = intensidad;
                pregunta = "";
            }
        }
        return ultima;
    }

    public static Integer maximaIntensidadLiteral(String relato) {
        String texto = normalizar(relato);
        if (!contiene(texto, "dolor|duele|duelen|cefalea")) return null;
        return INTENSIDAD.matcher(texto).results()
                .map(coincidencia -> Integer.valueOf(coincidencia.group(1)))
                .max(Integer::compareTo).orElse(null);
    }

    private static Integer extraerIntensidad(String texto, String pregunta) {
        var matcher = INTENSIDAD.matcher(texto);
        Integer ultima = null;
        while (matcher.find()) ultima = Integer.parseInt(matcher.group(1));
        if (ultima != null) {
            return contiene(pregunta + " " + texto, "dolor|duele|duelen|cefalea") ? ultima : null;
        }
        String respuesta = texto.replaceFirst("^(?:un|una)\\s+", "").replaceAll("[.!]$", "").strip();
        if (contiene(pregunta, "dolor|duele|duelen|cefalea")
                && contiene(pregunta, "intensidad|cuanto te duele|(?:0|1|cero|uno) (?:al|a) (?:10|diez)")) {
            if (respuesta.matches("10|[0-9]")) return Integer.parseInt(respuesta);
            List<String> numeros = List.of("cero", "uno", "dos", "tres", "cuatro", "cinco",
                    "seis", "siete", "ocho", "nueve", "diez");
            int indice = numeros.indexOf(respuesta);
            if (indice >= 0) return indice;
        }
        return null;
    }

    private static boolean esRespuesta(TurnoVoz turno) {
        return turno != null && turno.autor() == AutorMensaje.PACIENTE && textoValido(turno.contenido());
    }

    private static boolean textoValido(String texto) { return texto != null && !texto.isBlank(); }
    private static boolean listaValida(List<String> lista) {
        return lista != null && lista.stream().allMatch(ValidadorCierreEntrevistaVoz::textoValido);
    }
    private static boolean contiene(String texto, String alternativas) {
        return Pattern.compile("\\b(?:" + alternativas + ")\\b").matcher(texto).find();
    }
    private static boolean esNegacionBreve(String texto) {
        return texto.matches("(?:no|ninguno|ninguna|nada|nada mas)(?:,? (?:nada|nada mas|ninguno|ninguna|de eso|de esos|de esas))?[.!]?");
    }

    private static String normalizarConSiAfirmativo(String texto) {
        return normalizar(AFIRMACION_SI.matcher(texto).replaceAll(""));
    }

    private static boolean alarmaAfirmada(String texto) {
        // Split sentences and explicit contrasts so a previous denial does not negate a new affirmation.
        String textoSinCitas = TEXTO_CITADO.matcher(texto).replaceAll("");
        String textoConVariantesConversacionales = CUESTA_RESPIRAR_COLOQUIAL.matcher(textoSinCitas)
                .replaceAll("me cuesta respirar");
        textoConVariantesConversacionales = FALTA_AIRE_COLOQUIAL.matcher(textoConVariantesConversacionales)
                .replaceAll("falta de aire");
        for (String segmento : textoConVariantesConversacionales.split("[,;.!?\\n]|\\bpero\\b|\\bsin embargo\\b|\\by\\s+(?=no puedo respirar\\b)")) {
            if (CITA_ALARMA.matcher(segmento.strip()).matches()) continue;
            if (segmento.stripLeading().startsWith("¿")) continue;
            var alarma = Pattern.compile("\\b(?:" + ALARMAS + ")\\b").matcher(segmento);
            while (alarma.find()) {
                String prefijo = segmento.substring(0, alarma.start());
                if (HIPOTESIS_ALARMA.matcher(prefijo).find()) continue;
                var negacion = NEGACION.matcher(prefijo);
                int ultimaNegacion = -1;
                while (negacion.find()) ultimaNegacion = negacion.start();
                if (!esOrdenParaInventarAlarma(prefijo)
                        && (ultimaNegacion < 0 || AFIRMACION_TRAS_NEGACION.matcher(prefijo.substring(ultimaNegacion)).find())
                        && !contiene(segmento, "hace anos|en la infancia|antecedentes?")) return true;
            }
        }
        return false;
    }

    private static boolean esOrdenParaInventarAlarma(String prefijo) {
        return Pattern.compile("\\b(?:pon|ponga|agrega|agregue|anade|anada|incluye|incluya|marca|marque|anota|anote|escribe|escriba|registra|registre|inventa|invente)\\b.{0,60}$")
                .matcher(prefijo).find();
    }

    private static boolean alarmaResumenSignificativa(String texto) {
        String normalizado = normalizarConSiAfirmativo(texto);
        if (contiene(normalizado, "desconocido|desconocida|no informado|no informada|pendiente|ninguno|ninguna|negado|negados|ausente|ausentes|no aplica")) return false;
        // This helper sees summary fields, not verified patient testimony. Keep it limited
        // to the existing alarm vocabulary and do not let arbitrary prose count as an alarm.
        normalizado = FALTA_AIRE_COLOQUIAL.matcher(normalizado).replaceAll("falta de aire");
        return contiene(normalizado, ALARMAS) && alarmaAfirmada(normalizado);
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }
}
