package com.pretriage.backend.services.entrevista;

import com.pretriage.backend.controllers.dtos.TurnoVoz;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.services.voz.ValidadorCierreEntrevistaVoz;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, history-derived progress for one short clinical interview. */
public final class ProgresoEntrevista {
    public static final int MAX_TURNOS = 24;
    public static final int MAX_INTENTOS_POR_DATO = 3;
    public static final int MAX_SIN_AVANCE = 4;

    public enum Dimension { INICIO, INTENSIDAD, IMPACTO, TEMPERATURA }
    public enum Estado { PENDIENTE, RESPONDIDO, DESCONOCIDO, RECHAZADO, AGOTADO }
    public enum MotivoCierre { EN_CURSO, DATOS_RESUELTOS, ALARMA, SOLICITUD_PACIENTE, LIMITE_RECURSOS, SIN_AVANCE }
    public enum TipoObjetivo { DATO, INVITACION, ACLARACION, CERRAR }

    /** A turn excluded from triage remains visible for resource accounting and response context only. */
    public record Turno(AutorMensaje autor, String contenido, boolean excluido) { }

    /** Value is a literal, narrowly extracted patient answer; it is null for all unresolved/unknown states. */
    public record Dato(String sintomaId, Dimension dimension, Estado estado, int intentos, String valor) { }

    /** The next conversational action, derived from the same pending-datum selection as the instruction. */
    public record ObjetivoTurno(TipoObjetivo tipo, String sintomaId, Dimension dimension) { }

    private enum TipoTarea { NINGUNA, DATO, CONFIRMACION_MEDICION, INVITACION, ACLARACION }
    private record Clave(String sintomaId, Dimension dimension) { }
    private record Hallazgo(String sintomaId, String etiqueta, boolean fiebre,
                            int inicio, int fin, String segmento) { }
    private record Tarea(TipoTarea tipo, List<Clave> claves, String contenidoBot) { }
    private record SeleccionObjetivo(ObjetivoTurno objetivo, String etiqueta, int intentos, boolean medicionConfirmada) { }

    private static final Pattern NEGACION = Pattern.compile("\\b(?:no|sin|niega|niego|descarta|descarto|ningun[oa]s?)\\b");
    private static final Pattern AFIRMACION = Pattern.compile("(?:\\b(?:pero|aunque|si|y)\\s+|[,;]\\s*)(?:me|tengo|(?:lo\\s+)?siento|presento|aparecio|aparecen|noto)\\b");
    private static final Pattern ORDEN_INVENTAR = Pattern.compile("\\b(?:pon|ponga|agrega|agregue|anade|anada|incluye|incluya|marca|marque|anota|anote|escribe|escriba|registra|registre|inventa|invente)\\b.{0,60}$");
    private static final Pattern FECHA = Pattern.compile("\\b(?:desde\\s+(?:ayer|hoy|anoche|anteayer|el\\s+amanecer|esta\\s+(?:manana|tarde|noche)|que\\s+me\\s+dejo)|desde\\s+hace\\s+(?:un\\s+)?(?:rato|momento|poco)|hace\\s+\\d+\\s*(?:minutos?|horas?|dias?|semanas?|meses?|anos?)|(?:ayer|anoche|anteayer|hoy|el\\s+amanecer|esta\\s+(?:manana|tarde|noche)|hace\\s+\\S+\\s+(?:minutos?|horas?|dias?|semanas?|meses?|anos?))|(?:desde\\s+que\\s+(?:empezo|comenzo|aparecio)))\\b");
    private static final Pattern FECHA_TRAMO_DIA = Pattern.compile("\\s+(?:por|a|en)\\s+(?:la\\s+)?(?:manana|tarde|noche)\\b");
    private static final Pattern DURACION_COMPUESTA = Pattern.compile("\\bhace\\s+\\d+\\s*(?:minutos?|horas?|dias?|semanas?|meses?|anos?)(?:\\s+y\\s+\\d+\\s*(?:minutos?|horas?|dias?|semanas?|meses?|anos?))*\\b");
    private static final Pattern INICIO_RELATIVO = Pattern.compile("\\bdesde\\s+que\\s+[^,;.!?]{1,80}");
    private static final Pattern MEDICION = Pattern.compile(
            "(?<![\\d.,])\\b(\\d{2}(?:[.,]\\d)?)(?![.,]\\d)\\s*(?:grados?|°(?:\\s*c)?|celsius)"
                    + "(?![\\p{L}\\p{N}])(?:\\s+y\\s+medio\\b)?"
                    + "|\\btemperatura\\s*(?:de|:)?\\s*(\\d{2}(?:[.,]\\d)?)(?![.,]\\d)(?![\\p{L}\\p{N}])"
                    + "(?!(?:\\s*(?:grados?|°(?:\\s*c)?|celsius)))(?:\\s+y\\s+medio\\b)?");
    private static final Pattern IMPACTO = Pattern.compile("\\b(?:mucho|muchisimo|bastante|poco|muy poco|nada|un poco|si|no|me limita|no me limita|me impide|no me impide|no puedo (?:trabajar|dormir|comer|caminar|hacer mis actividades)|me cuesta (?:trabajar|dormir|comer|caminar))\\b");
    private static final Pattern IMPACTO_CUALIFICADO_FUNCIONAL = Pattern.compile(
            "^(?:mucho|muchisimo|bastante|poco|muy poco|nada|un poco),?\\s+puedo\\s+(?:hacer|realizar)\\s+(?:mis|las)\\s+actividades(?:\\s+habituales)?[.!]?$");
    private static final Pattern TEMPERATURA_BARE = Pattern.compile("^(\\d{2}(?:[.,]\\d)?)$|^(\\d{2}(?:[.,]\\d)?)[.!]$");
    private static final Pattern ANAFORA_PLURAL_SINTOMAS = Pattern.compile("\\b(?:estos|estas|esos|esas)\\s+(?:sintomas|signos|molestias)\\b");
    private static final Pattern PREGUNTA_VALOR_TERMOMETRO_ANTES = Pattern.compile("\\b(?:numero|valor|medicion)\\b.{0,40}\\b(?:termometro|temperatura)\\b");
    private static final Pattern PREGUNTA_VALOR_TERMOMETRO_DESPUES = Pattern.compile("\\b(?:termometro|temperatura)\\b.{0,40}\\b(?:numero|valor|mostro|marco|vio)\\b");
    private static final Pattern UNKNOWN = Pattern.compile("\\b(?:no se|no recuerdo|desconozco|no lo se|no la medi|no me la medi|no medi|no me medi|no se mi temperatura|no recuerdo mi temperatura)\\b");
    private static final Pattern REFUSAL = Pattern.compile("\\b(?:no quiero|no deseo|prefiero no|no voy a|no puedo)\\s+(?:decir|responder|contestar|informar|medir|compartir)\\b|\\bno quiero hablar de eso\\b");
    private static final Pattern STOP = Pattern.compile("^(?:(?:por favor|yo)\\s+)?(?:no quiero seguir(?: respondiendo(?: preguntas?)?)?|no quiero continuar|no quiero responder mas|no deseo seguir|quiero terminar(?: la entrevista| el chat)?|prefiero terminar(?: la entrevista| el chat)?|terminemos(?: la entrevista| el chat)?|dejemoslo aca|termina el chat|termina la entrevista|finaliza(?: el chat| la entrevista)?)(?:\\s+(?:ahora|por favor|porfa|ya|porque\\b.{0,60}|estoy (?:cansado|cansada|agotado|agotada)))?$");
    private static final Pattern INVITATION = Pattern.compile("\\b(?:algo mas|otra cosa|algo para agregar|algo que quieras (?:contar|agregar)|queres agregar algo|quieres agregar algo|tenes algun otro sintoma|hay algun otro sintoma|que mas queres contar)\\b");
    private static final Pattern ACLARACION_ABIERTA = Pattern.compile("\\b(?:podrias|podes|puedes|decime|dime|explica(?:me)?|aclara(?:me)?|describe(?:me)?|contame|cuentame)\\b");
    private static final Pattern EVOLUCION_BREVE = Pattern.compile(
            "^(?:sigue igual|esta igual|sin cambios|sigue sin cambios|sigue mejor|sigue peor|sigue mejorando|sigue empeorando|va mejor|va peor)[.!]?$");
    private static final Pattern ADDITIONAL_INFORMATION_CUE = Pattern.compile("\\b(?:algo\\s+mas|mas|otra cosa|otro[s]?\\s+(?:detalle|dato|aspecto|tema)|alguna otra\\s+(?:cosa|idea)|detalle adicional)\\b");
    private static final Pattern CONTRIBUTION_VERB = Pattern.compile("\\b(?:(?:queres|quieres|quieras|deseas|te gustaria|podrias|podes)\\s+)?(?:contar(?:me|nos)?|compartir(?:me)?|agregar|anadir|decir(?:me|nos)?|comentar(?:me)?|mencionar|sumar)\\b");
    private static final Pattern REGISTRATION_OR_CLOSURE = Pattern.compile("\\b(?:registrad[oa]s?|registro|registrar|registrarse|cerrar|cierre|finalizad[oa]s?|finalizar|terminad[oa]s?|terminar)\\b");
    private static final Pattern EMOTION = Pattern.compile("\\b(?:ansiedad|angustia|tristeza|triste|depresion|deprimid[oa]s?|duelo|soledad|desesperanza|malestar emocional|me siento mal emocionalmente|me siento (?:un )?vaci[oa]|la extrano|lo extrano|me dejo|me abandono|nos separamos|perdi a (?:mi|un|una))\\b");
    private static final String UBICACIONES_DOLOR = "cabeza|pecho|torax|abdomen|panza|barriga|estomago|espalda|cuello|garganta|mano|manos|brazo|brazos|pierna|piernas|rodilla|rodillas|diente|dientes|muela|muelas|ojo|ojos|pie|pies";
    private static final Pattern LOCATION_PAIN = Pattern.compile("\\b(?:dolor(?:es)?(?:\\s+(?:de|en|del|en el|en la))?|(?:me|te)\\s+duele(?:n)?|duele(?:n)?)\\s+(?:la|el|los|las)?\\s*(" + UBICACIONES_DOLOR + ")\\b");
    private static final Pattern PAIRED_LOCATION_PAIN = Pattern.compile("\\b(?:(?:me|te)\\s+duele(?:n)?|duele(?:n)?|dolor(?:es)?(?:\\s+(?:de|en|del|en el|en la))?)\\s+(?:la|el|los|las|una|un)?\\s*(?:" + UBICACIONES_DOLOR + ")\\s+y\\s+(?:(?:de|en|del|en el|en la)\\s+)?(?:la|el|los|las|una|un)?\\s*(" + UBICACIONES_DOLOR + ")\\b");
    private static final List<SymptomSpec> SYMPTOMS = List.of(
            new SymptomSpec("fiebre", "la fiebre", true, "\\b(?:fiebre|febril)\\b"),
            new SymptomSpec("tos", "la tos", false, "\\b(?:tos|toser)\\b"),
            new SymptomSpec("nauseas", "las náuseas", false, "\\b(?:nauseas?|ganas de vomitar)\\b"),
            new SymptomSpec("vomitos", "los vómitos", false, "\\b(?:vomitos?|vomitar)\\b"),
            new SymptomSpec("mareos", "los mareos", false, "\\b(?:mareos?|vertigo)\\b"),
            new SymptomSpec("diarrea", "la diarrea", false, "\\bdiarrea\\b"),
            new SymptomSpec("sangrado", "el sangrado", false, "\\b(?:sangrado|sangrar)\\b"),
            new SymptomSpec("debilidad", "la debilidad", false, "\\bdebilidad\\b"),
            new SymptomSpec("rash", "la erupción o sarpullido", false, "\\b(?:erupcion|sarpullido|ronchas?)\\b"),
            new SymptomSpec("picazon", "la picazón", false, "\\b(?:picazon|me pica)\\b"),
            new SymptomSpec("hinchazon", "la hinchazón", false, "\\bhinchazon\\b"),
            new SymptomSpec("hormigueo", "el hormigueo", false, "\\b(?:hormigueo|entumecimiento)\\b"),
            new SymptomSpec("ardor", "el ardor", false, "\\b(?:ardor|me arde)\\b"),
            new SymptomSpec("quemadura", "la quemadura", false, "\\b(?:quemadura|quemaduras|me queme|me quem[eé])\\b"),
            new SymptomSpec("lesion", "la lesión", false, "\\b(?:lesion(?:es)?|herida(?:s)?|corte(?:s)?)\\b"),
            new SymptomSpec("hematoma", "el moretón", false, "\\b(?:moraton(?:es)?|moreton(?:es)?|hematoma(?:s)?)\\b"),
            new SymptomSpec("traumatismo", "el golpe o torcedura", false, "\\b(?:golpe(?:s)?|torcedura|torci|lastimad[oa]s?)\\b"),
            new SymptomSpec("molestia", "la molestia", false, "\\bmolestia(?:s)?\\b")
    );

    private final List<Dato> datos;
    private final MotivoCierre motivoCierre;
    private final String instruccionSiguientePaso;
    private final boolean requiereAclaracion;
    private final boolean tieneHechosClinicos;
    private final boolean ultimoTurnoPacienteTieneHechos;
    private final ObjetivoTurno objetivoTurno;
    private final boolean medicionConfirmadaObjetivo;
    private final boolean esperaRespuestaPaciente;

    private ProgresoEntrevista(List<Dato> datos, MotivoCierre motivoCierre,
                               String instruccionSiguientePaso, boolean requiereAclaracion,
                               boolean tieneHechosClinicos, boolean ultimoTurnoPacienteTieneHechos,
                               ObjetivoTurno objetivoTurno, boolean medicionConfirmadaObjetivo,
                               boolean esperaRespuestaPaciente) {
        this.datos = List.copyOf(datos);
        this.motivoCierre = Objects.requireNonNull(motivoCierre);
        this.instruccionSiguientePaso = Objects.requireNonNull(instruccionSiguientePaso);
        this.requiereAclaracion = requiereAclaracion;
        this.tieneHechosClinicos = tieneHechosClinicos;
        this.ultimoTurnoPacienteTieneHechos = ultimoTurnoPacienteTieneHechos;
        this.objetivoTurno = Objects.requireNonNull(objetivoTurno);
        this.medicionConfirmadaObjetivo = medicionConfirmadaObjetivo;
        this.esperaRespuestaPaciente = esperaRespuestaPaciente;
    }

    public static ProgresoEntrevista evaluar(List<TurnoVoz> historial) {
        List<Turno> turnos = historial == null ? List.of() : historial.stream()
                .map(t -> t == null ? new Turno(null, null, false) : new Turno(t.autor(), t.contenido(), false))
                .toList();
        return evaluarConContexto(turnos);
    }

    public static ProgresoEntrevista evaluarConContexto(List<Turno> historial) {
        return new Builder(historial == null ? List.of() : historial).build();
    }

    public boolean puedeCerrar() { return motivoCierre != MotivoCierre.EN_CURSO; }
    public MotivoCierre motivoCierre() { return motivoCierre; }
    public String instruccionSiguientePaso() { return instruccionSiguientePaso; }
    public List<Dato> datos() { return datos; }
    public boolean requiereAclaracion() { return requiereAclaracion; }
    public boolean tieneHechosClinicos() { return tieneHechosClinicos; }
    public boolean ultimoTurnoPacienteTieneHechos() { return ultimoTurnoPacienteTieneHechos; }
    public boolean esperaRespuestaPaciente() { return esperaRespuestaPaciente; }
    public ObjetivoTurno objetivoDelTurno() { return objetivoTurno; }

    /** Returns only fixed, non-sensitive local validation codes; unrecognized phrasing is delegated remotely. */
    public Optional<String> validarPreguntaPropuesta(String pregunta) {
        return validarPreguntaPropuesta(objetivoTurno, pregunta, medicionConfirmadaObjetivo);
    }

    private Optional<String> validarPreguntaPropuesta(ObjetivoTurno objetivo, String pregunta,
                                                       boolean medicionConfirmada) {
        if (pregunta == null || pregunta.isBlank()) return Optional.empty();
        String text = norm(pregunta);
        boolean invitacion = INVITATION.matcher(text).find();
        boolean cierre = containsClosingPhrase(text);
        Dimension proposedDimension = askedDimension(text);
        List<String> mentioned = symptoms(text).stream().map(Hallazgo::sintomaId).distinct().toList();
        if (objetivo.tipo() == TipoObjetivo.CERRAR) {
            return hasQuestionForm(text) || proposedDimension != null || invitacion
                    ? Optional.of("PREGUNTA_TRAS_CIERRE") : Optional.empty();
        }
        if (objetivo.tipo() == TipoObjetivo.DATO) {
            if (cierre || invitacion) return Optional.of("PREGUNTA_PREMATURA");
            boolean medicionSubpaso = objetivo.dimension() == Dimension.TEMPERATURA
                    && isMeasurementConfirmationQuestion(text);
            if (medicionSubpaso) return medicionConfirmada
                    ? Optional.of("CONFIRMACION_MEDICION_REPETIDA") : Optional.empty();
            if (proposedDimension == null) {
                return mentioned.isEmpty() || preguntaMencionaSoloObjetivo(mentioned, objetivo.sintomaId())
                        ? Optional.empty() : Optional.of("SINTOMA_NO_COINCIDE");
            }
            boolean impactoDelMismoDolor = objetivo.dimension() == Dimension.INTENSIDAD
                    && proposedDimension == Dimension.IMPACTO
                    && objetivo.sintomaId() != null && objetivo.sintomaId().startsWith("dolor:");
            if (proposedDimension != objetivo.dimension() && !impactoDelMismoDolor) {
                return Optional.of("DIMENSION_NO_COINCIDE");
            }
            if (!mentioned.isEmpty() && !preguntaMencionaSoloObjetivo(mentioned, objetivo.sintomaId())) {
                return Optional.of("SINTOMA_NO_COINCIDE");
            }
            if (mentioned.isEmpty() && hayVariosCandidatosParaPregunta(objetivo, proposedDimension)) {
                return Optional.of("OBJETIVO_AMBIGUO");
            }
            Dato actual = datos.stream().filter(d -> Objects.equals(d.sintomaId(), objetivo.sintomaId())
                    && d.dimension() == objetivo.dimension()).findFirst().orElse(null);
            if (actual != null && actual.estado() == Estado.AGOTADO) return Optional.of("DATO_AGOTADO");
            return Optional.empty();
        }
        if (objetivo.tipo() == TipoObjetivo.INVITACION) {
            if (cierre) return Optional.of("CIERRE_PREMATURO");
            if (!invitacion) return proposedDimension != null || !mentioned.isEmpty()
                    ? Optional.of("PREGUNTA_PREMATURA") : Optional.empty();
            return mentioned.isEmpty() || preguntaMencionaSoloSintomasConocidos(mentioned)
                    ? Optional.empty() : Optional.of("SINTOMAS_SUGERIDOS");
        }
        if (objetivo.tipo() == TipoObjetivo.ACLARACION && objetivo.dimension() == Dimension.TEMPERATURA
                && medicionConfirmada && isMeasurementConfirmationQuestion(text)) {
            return Optional.of("CONFIRMACION_MEDICION_REPETIDA");
        }
        // ACLARACION may restate the current datum or ask an open clarification, but may not change topic.
        if (cierre || invitacion) return Optional.of("ACLARACION_FUERA_DE_OBJETIVO");
        if (objetivo.sintomaId() == null && proposedDimension != null) return Optional.of("ACLARACION_FUERA_DE_OBJETIVO");
        if (objetivo.sintomaId() == null) {
            boolean soloMolestiaGenerica = !mentioned.isEmpty()
                    && mentioned.stream().allMatch("sintoma:molestia"::equals);
            return mentioned.isEmpty() || soloMolestiaGenerica
                    ? Optional.empty() : Optional.of("ACLARACION_FUERA_DE_OBJETIVO");
        }
        if (!mentioned.isEmpty() && !preguntaMencionaSoloObjetivo(mentioned, objetivo.sintomaId())) {
            return Optional.of("SINTOMA_NO_COINCIDE");
        }
        if (proposedDimension != null && objetivo.dimension() != null && proposedDimension != objetivo.dimension()) {
            return Optional.of("DIMENSION_NO_COINCIDE");
        }
        return Optional.empty();
    }

    private boolean preguntaMencionaSoloSintomasConocidos(List<String> mentioned) {
        long knownPainIds = datos.stream().map(Dato::sintomaId).filter(id -> id.startsWith("dolor:")).distinct().count();
        return mentioned.stream().allMatch(id -> datos.stream().anyMatch(d -> d.sintomaId().equals(id))
                || (id.equals("dolor:general") && knownPainIds == 1));
    }

    private boolean preguntaMencionaSoloObjetivo(List<String> mentioned, String objetivoId) {
        if (objetivoId == null) return false;
        if (mentioned.stream().allMatch(objetivoId::equals)) return true;
        boolean genericPainAlias = mentioned.size() == 1 && mentioned.contains("dolor:general")
                && objetivoId.startsWith("dolor:") && !objetivoId.equals("dolor:general");
        long distinctPainIds = datos.stream().map(Dato::sintomaId).filter(id -> id.startsWith("dolor:")).distinct().count();
        return genericPainAlias && distinctPainIds == 1;
    }

    private boolean hayVariosCandidatosParaPregunta(ObjetivoTurno objetivo, Dimension dimension) {
        long candidatos = datos.stream().filter(d -> d.estado() == Estado.PENDIENTE && d.dimension() == dimension)
                .map(Dato::sintomaId).distinct().count();
        if (dimension == Dimension.IMPACTO && objetivo.dimension() == Dimension.INTENSIDAD
                && objetivo.sintomaId() != null && objetivo.sintomaId().startsWith("dolor:")) {
            candidatos += datos.stream().filter(d -> d.estado() == Estado.PENDIENTE
                            && d.dimension() == Dimension.INTENSIDAD && d.sintomaId().equals(objetivo.sintomaId()))
                    .count();
        }
        return candidatos > 1;
    }

    private static boolean hasQuestionForm(String text) {
        return text.contains("?") || text.matches("(?s)^\\s*(?:que|como|cuando|desde cuando|cuanto|cual|tenes|tienes|pudiste|podrias|quieres|queres)\\b.*");
    }

    private static boolean containsClosingPhrase(String text) {
        return text.matches("(?s).*\\b(?:ya se registro|caso(?:\\s+ya)?(?:\\s+(?:se|fue|ha sido))?\\s+registrado|entrevista finalizada|hemos terminado|gracias por responder|listo,? ya esta)\\b.*");
    }

    private record SymptomSpec(String id, String label, boolean fever, String regex) { }

    private static final class Tracker {
        private final Clave clave;
        private final String etiqueta;
        private Dimension dimension;
        private Estado estado = Estado.PENDIENTE;
        private int intentos;
        private String valor;
        private boolean medicionConfirmada;
        private Tracker(Clave clave, String etiqueta) {
            this.clave = clave;
            this.etiqueta = etiqueta;
            this.dimension = clave.dimension();
        }
        private void set(Estado nuevo, String nuevoValor) {
            estado = nuevo;
            valor = nuevo == Estado.RESPONDIDO ? nuevoValor : null;
        }
    }

    private static final class Builder {
        private final List<Turno> history;
        private final LinkedHashMap<Clave, Tracker> trackers = new LinkedHashMap<>();
        private Tarea tarea = new Tarea(TipoTarea.NINGUNA, List.of(), "");
        private Tarea tareaAclarar;
        private int noAvance;
        private int totalRespuestas;
        private boolean motivoClinico;
        private boolean emocional;
        private boolean hechosClinicos;
        private boolean ultimoTurnoPacienteTieneHechos;
        private boolean alarma;
        private boolean stop;
        private boolean invitacionAbierta;
        private boolean respondioInvitacion;
        private boolean invitacionAporteOpacoPendiente;
        private boolean invitacionAporteNoClaro;
        private boolean yaDijoQueNoAgrega;
        private boolean requiereAclaracion;
        private boolean preguntaDatoTienePresupuesto;

        private Builder(List<Turno> history) { this.history = history; }

        private ProgresoEntrevista build() {
            for (Turno turn : history) process(turn);
            MotivoCierre reason = determineReason();
            SeleccionObjetivo selection = seleccionarObjetivo(reason);
            String instruction = instruction(reason, selection);
            List<Dato> output = trackers.values().stream()
                    .map(t -> new Dato(t.clave.sintomaId(), t.dimension, t.estado, t.intentos, t.valor))
                    .toList();
            return new ProgresoEntrevista(output, reason, instruction, requiereAclaracion, hechosClinicos,
                    ultimoTurnoPacienteTieneHechos, selection.objetivo(), selection.medicionConfirmada(),
                    esperaRespuestaPacienteEnTarea());
        }

        private boolean esperaRespuestaPacienteEnTarea() {
            return switch (tarea.tipo()) {
                case INVITACION, ACLARACION -> true;
                case DATO -> preguntaDatoTienePresupuesto && tarea.claves().stream()
                        .map(trackers::get)
                        .anyMatch(target -> target != null && target.estado == Estado.PENDIENTE);
                case CONFIRMACION_MEDICION -> tarea.claves().stream()
                        .map(trackers::get)
                        .anyMatch(target -> target != null && target.estado == Estado.PENDIENTE
                                && !target.medicionConfirmada);
                case NINGUNA -> false;
            };
        }

        private void process(Turno turn) {
            if (turn == null) return;
            String text = norm(turn.contenido());
            if (turn.autor() == AutorMensaje.BOT) {
                processBot(text);
                return;
            }
            if (turn.autor() != AutorMensaje.PACIENTE || turn.contenido() == null || turn.contenido().isBlank()) return;
            text = patientEvidence(text);
            totalRespuestas++;
            boolean advanced = false;
            ultimoTurnoPacienteTieneHechos = false;
            requiereAclaracion = false;
            if (!turn.excluido()) {
                stop |= stopSolicitado(text); // explicit patient intent is not a clinical fact
                boolean alarmaTurno = ValidadorCierreEntrevistaVoz.alarmaLiteralAfirmada(turn.contenido());
                alarma |= alarmaTurno;
                boolean emocionTurno = affirmatedPattern(text, EMOTION);
                emocional |= emocionTurno;
                hechosClinicos |= alarma || emocional;
                List<Hallazgo> found = bindGenericPainReferences(symptoms(text), text);
                ultimoTurnoPacienteTieneHechos = alarmaTurno || emocionTurno || !found.isEmpty();
                if (!found.isEmpty()) {
                    motivoClinico = true;
                    hechosClinicos = true;
                    ultimoTurnoPacienteTieneHechos = true;
                }
                for (Hallazgo symptom : found) {
                    advanced |= ensureObjectives(symptom);
                    advanced |= applySpontaneous(symptom, text);
                }
                advanced |= applyUnpromptedUnknown(text);
                if (found.isEmpty() && tarea.tipo() == TipoTarea.NINGUNA) {
                    advanced |= applyUnpromptedUniquePainScale(text);
                }
                if (isNoMoreSymptoms(text)) yaDijoQueNoAgrega = true;
            }

            if (tarea.tipo() == TipoTarea.INVITACION) {
                if (!turn.excluido()) {
                    if (esDeclinacionInvitacion(text)) {
                        respondioInvitacion = true;
                        yaDijoQueNoAgrega = true;
                        advanced = true;
                    } else if (ultimoTurnoPacienteTieneHechos) {
                        respondioInvitacion = true;
                        invitacionAporteOpacoPendiente = false;
                        advanced = true;
                    } else if (evolucionBreveDeMotivoConocido(text)) {
                        respondioInvitacion = true;
                        invitacionAporteOpacoPendiente = false;
                        ultimoTurnoPacienteTieneHechos = true;
                        advanced = true;
                    } else {
                        invitacionAporteOpacoPendiente = true;
                        requiereAclaracion = true;
                    }
                }
            } else if (tarea.tipo() == TipoTarea.CONFIRMACION_MEDICION && !tarea.claves().isEmpty()) {
                for (Clave key : tarea.claves()) {
                    Tracker target = trackers.get(key);
                    if (target == null || target.estado != Estado.PENDIENTE || turn.excluido()) continue;
                    String valor = extractTemperature(text, true);
                    if (valor != null) {
                        target.set(Estado.RESPONDIDO, valor);
                        target.medicionConfirmada = true;
                        tareaAclarar = null;
                        advanced = true;
                    } else if (UNKNOWN.matcher(text).find() || isNegativeMeasurementConfirmation(text)
                            || text.matches("^no[.!]?$")) {
                        target.set(Estado.DESCONOCIDO, null);
                        tareaAclarar = null;
                        advanced = true;
                    } else if (isPositiveMeasurementConfirmation(text) && !target.medicionConfirmada) {
                        target.medicionConfirmada = true;
                        tareaAclarar = null;
                        advanced = true;
                    }
                }
            } else if (tarea.tipo() == TipoTarea.DATO && !tarea.claves().isEmpty()) {
                for (Clave key : tarea.claves()) {
                    Tracker target = trackers.get(key);
                    if (target != null && (target.estado == Estado.PENDIENTE || target.estado == Estado.AGOTADO)) {
                        if (turn.excluido()) {
                            invalidAnswer(target);
                            tareaAclarar = tarea;
                        } else {
                            ResultadoRespuesta result = answer(target, text);
                            if (result.estado() != null) {
                                target.set(result.estado(), result.valor());
                                tareaAclarar = null;
                                advanced = true;
                            } else {
                                invalidAnswer(target);
                                tareaAclarar = tarea;
                            }
                        }
                    }
                }
            } else if (tarea.tipo() == TipoTarea.ACLARACION && !turn.excluido()) {
                // Clarifying a previously reported physical vacuum makes that report usable.
                if (physicalAffirmation(text) && priorVacuum(history)) {
                    Hallazgo vacuum = new Hallazgo("sensacion:vacio_pecho", "la sensación de vacío", false, 0, 0, text);
                    motivoClinico = true;
                    hechosClinicos = true;
                    ultimoTurnoPacienteTieneHechos = true;
                    advanced |= ensureObjectives(vacuum);
                }
                boolean clasificacionEmocional = affirmatedPattern(text, Pattern.compile("\\b(?:emocional|emocionalmente)\\b"));
                if (clasificacionEmocional) {
                    emocional = true;
                    hechosClinicos = true;
                    ultimoTurnoPacienteTieneHechos = true;
                    advanced = true;
                }
                advanced |= !symptoms(text).isEmpty() || affirmatedPattern(text, EMOTION);
                if (invitacionAbierta && !respondioInvitacion) {
                    if (esDeclinacionInvitacion(text)) {
                        respondioInvitacion = true;
                        invitacionAporteOpacoPendiente = false;
                        yaDijoQueNoAgrega = true;
                        advanced = true;
                    } else if (ultimoTurnoPacienteTieneHechos) {
                        respondioInvitacion = true;
                        invitacionAporteOpacoPendiente = false;
                        advanced = true;
                    } else if (evolucionBreveDeMotivoConocido(text)) {
                        respondioInvitacion = true;
                        invitacionAporteOpacoPendiente = false;
                        ultimoTurnoPacienteTieneHechos = true;
                        advanced = true;
                    } else if (invitacionAporteOpacoPendiente) {
                        invitacionAporteOpacoPendiente = false;
                        invitacionAporteNoClaro = true;
                    }
                }
                if (!advanced && !invitacionAporteNoClaro) requiereAclaracion = true;
            } else if (tarea.tipo() == TipoTarea.NINGUNA && !turn.excluido()
                    && exploratoryAnswer(tarea.contenidoBot(), text)) {
                // A bounded negative/unknown answer to an explicit safety or history
                // question is conversational progress, not a newly reported symptom.
                advanced = true;
            } else if (!motivoClinico && !emocional && !turn.excluido()) {
                requiereAclaracion = true;
            }

            if (!turn.excluido() && invitacionAbierta && !respondioInvitacion
                    && tarea.tipo() == TipoTarea.NINGUNA && invitacionAporteOpacoPendiente) {
                if (esDeclinacionInvitacion(text)) {
                    respondioInvitacion = true;
                    invitacionAporteOpacoPendiente = false;
                    yaDijoQueNoAgrega = true;
                    advanced = true;
                } else if (ultimoTurnoPacienteTieneHechos) {
                    respondioInvitacion = true;
                    invitacionAporteOpacoPendiente = false;
                    advanced = true;
                } else if (evolucionBreveDeMotivoConocido(text)) {
                    respondioInvitacion = true;
                    invitacionAporteOpacoPendiente = false;
                    ultimoTurnoPacienteTieneHechos = true;
                    advanced = true;
                } else {
                    invitacionAporteOpacoPendiente = false;
                    invitacionAporteNoClaro = true;
                    requiereAclaracion = false;
                }
            }

            if (!turn.excluido() && invitacionAporteNoClaro
                    && (advanced || ultimoTurnoPacienteTieneHechos)) {
                invitacionAporteNoClaro = false;
                invitacionAporteOpacoPendiente = false;
                respondioInvitacion = true;
            }

            if (advanced) noAvance = 0;
            else noAvance++;
            tarea = new Tarea(TipoTarea.NINGUNA, List.of(), "");
        }

        private void processBot(String text) {
            // Preserve the unresolved objective from the patient turn that led to this prompt.
            // An open clarification is still an interview task even when it has no dimension words.
            boolean aclaracionAbiertaPendiente = requiereAclaracion && tareaAclarar == null;
            SeleccionObjetivo objetivoAntesDelBot = seleccionarObjetivo(determineReason());
            requiereAclaracion = false;
            if (aclaracionAbiertaPendiente) {
                if (hasQuestionForm(text) || ACLARACION_ABIERTA.matcher(text).find()) {
                    tarea = new Tarea(TipoTarea.ACLARACION, List.of(), text);
                } else {
                    tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                    requiereAclaracion = true;
                }
                return;
            }
            if (INVITATION.matcher(text).find()) {
                if (!respondioInvitacion) {
                    invitacionAbierta = true;
                    tarea = new Tarea(TipoTarea.INVITACION, List.of(), text);
                } else {
                    tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                }
                return;
            }
            if ((hasQuestionForm(text) || ACLARACION_ABIERTA.matcher(text).find())
                    && text.contains("emocional") && (text.contains("fisic") || text.contains("corporal"))) {
                tarea = new Tarea(TipoTarea.ACLARACION, List.of(), text);
                return;
            }
            if (isMeasurementConfirmationQuestion(text)) {
                List<Tracker> temperatures = trackers.values().stream()
                        .filter(t -> t.estado == Estado.PENDIENTE && t.dimension == Dimension.TEMPERATURA).toList();
                List<String> mentioned = symptoms(text).stream().map(Hallazgo::sintomaId).distinct().toList();
                List<Tracker> named = temperatures.stream().filter(t -> mentioned.contains(t.clave.sintomaId())).toList();
                List<Tracker> targets = named.size() == 1 ? named : (mentioned.isEmpty() && temperatures.size() == 1 ? temperatures : List.of());
                if (targets.size() == 1) {
                    tarea = new Tarea(TipoTarea.CONFIRMACION_MEDICION,
                            targets.stream().map(t -> t.clave).toList(), text);
                } else {
                    tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                }
                return;
            }
            Dimension dimension = askedDimension(text);
            if (dimension == null) {
                List<String> mencionados = symptoms(text).stream().map(Hallazgo::sintomaId).distinct().toList();
                boolean mencionaSoloDatosConocidos = mencionados.isEmpty()
                        || mencionados.stream().allMatch(this::sintomaConocidoParaBuilder);
                if (objetivoAntesDelBot.objetivo().tipo() == TipoObjetivo.INVITACION
                        && hasQuestionForm(text) && esPreguntaDeAporteAdicional(text) && mencionaSoloDatosConocidos
                        && !containsClosingPhrase(text) && !REGISTRATION_OR_CLOSURE.matcher(text).find()) {
                    invitacionAbierta = true;
                    tarea = new Tarea(TipoTarea.INVITACION, List.of(), text);
                    return;
                }
                if ((hasQuestionForm(text) || ACLARACION_ABIERTA.matcher(text).find())
                        && tareaAclarar == null && !motivoClinico && !emocional) {
                    tarea = new Tarea(TipoTarea.ACLARACION, List.of(), text);
                    return;
                }
                tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                return;
            }
            List<String> mentioned = symptoms(text).stream().map(Hallazgo::sintomaId).toList();
            List<Tracker> candidates = trackers.values().stream()
                    .filter(t -> t.estado == Estado.PENDIENTE && t.dimension == dimension)
                    .toList();
            if (candidates.isEmpty() && dimension == Dimension.IMPACTO) {
                candidates = trackers.values().stream().filter(t -> t.estado == Estado.PENDIENTE
                        && t.dimension == Dimension.INTENSIDAD).toList();
            }
            if (candidates.isEmpty() && dimension == Dimension.INTENSIDAD
                    && !text.contains("dolor") && !text.contains("duele")) {
                candidates = trackers.values().stream().filter(t -> t.estado == Estado.PENDIENTE
                        && t.dimension == Dimension.IMPACTO).toList();
            }
            if (dimension != null) {
                List<Tracker> agotados = trackers.values().stream()
                        .filter(t -> t.estado == Estado.AGOTADO && t.dimension == dimension).toList();
                List<Tracker> agotadoNombrado = agotados.stream()
                        .filter(t -> mentioned.contains(t.clave.sintomaId())).toList();
                if (agotadoNombrado.size() == 1) candidates = agotadoNombrado;
                else if (candidates.isEmpty() && mentioned.isEmpty() && agotados.size() == 1) candidates = agotados;
            }
            if (candidates.isEmpty()) {
                tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                return;
            }
            List<Tracker> explicitlyMentioned = candidates.stream()
                    .filter(t -> mentioned.contains(t.clave.sintomaId())).toList();
            if (mentioned.contains("dolor:general")) {
                List<Tracker> painCandidates = candidates.stream().filter(t -> t.clave.sintomaId().startsWith("dolor:")).toList();
                if (painCandidates.size() == 1 && explicitlyMentioned.stream()
                        .noneMatch(t -> t.clave.sintomaId().equals(painCandidates.get(0).clave.sintomaId()))) {
                    var compatible = new java.util.ArrayList<>(explicitlyMentioned);
                    compatible.add(painCandidates.get(0));
                    explicitlyMentioned = List.copyOf(compatible);
                }
            }
            if (!mentioned.isEmpty() && explicitlyMentioned.isEmpty()) {
                tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                return;
            }
            boolean anaforaPlural = ANAFORA_PLURAL_SINTOMAS.matcher(text).find();
            boolean referenciaBinaria = candidates.size() == 2
                    && text.matches(".*\\b(?:ambos|ambas|los dos|las dos)\\b.*");
            boolean preguntaCompartida = referenciaBinaria
                    || anaforaPlural && dimension == Dimension.INICIO && candidates.size() > 1
                    || explicitlyMentioned.size() > 1;
            if (mentioned.isEmpty() && candidates.size() > 1 && !preguntaCompartida) {
                tarea = new Tarea(TipoTarea.NINGUNA, List.of(), text);
                return;
            }
            List<Tracker> targets = !explicitlyMentioned.isEmpty()
                    ? explicitlyMentioned
                    : anaforaPlural && dimension == Dimension.INICIO && candidates.size() > 1
                        ? candidates
                        : referenciaBinaria && candidates.size() == 2 ? candidates : List.of(candidates.get(0));
            if (!(preguntaCompartida && targets.size() > 1)) {
                targets = List.of(targets.get(0));
            }
            preguntaDatoTienePresupuesto = targets.stream()
                    .anyMatch(target -> target.estado == Estado.PENDIENTE
                            && target.intentos < MAX_INTENTOS_POR_DATO);
            for (Tracker target : targets) {
                if (target.intentos < MAX_INTENTOS_POR_DATO) target.intentos++;
            }
            for (Tracker target : targets) {
                if (dimension == Dimension.IMPACTO && target.dimension == Dimension.INTENSIDAD) {
                    target.dimension = Dimension.IMPACTO;
                }
            }
            // An answer to a different dimension never completes this target.
            tarea = new Tarea(TipoTarea.DATO, targets.stream().map(t -> t.clave).toList(), text);
        }

        private boolean sintomaConocidoParaBuilder(String id) {
            if (trackers.keySet().stream().anyMatch(key -> key.sintomaId().equals(id))) return true;
            long knownPainIds = trackers.keySet().stream().map(Clave::sintomaId)
                    .filter(sintomaId -> sintomaId.startsWith("dolor:") && !sintomaId.equals("dolor:general"))
                    .distinct().count();
            return id.equals("dolor:general") && knownPainIds == 1;
        }

        private boolean esPreguntaDeAporteAdicional(String text) {
            return ADDITIONAL_INFORMATION_CUE.matcher(text).find()
                    && CONTRIBUTION_VERB.matcher(text).find();
        }

        private boolean evolucionBreveDeMotivoConocido(String text) {
            long motivosConocidos = trackers.keySet().stream()
                    .map(Clave::sintomaId).distinct().count();
            return motivoClinico && motivosConocidos == 1 && EVOLUCION_BREVE.matcher(text.strip()).matches();
        }

        private boolean ensureObjectives(Hallazgo symptom) {
            boolean added = false;
            added |= ensure(new Clave(symptom.sintomaId(), Dimension.INICIO), symptom.etiqueta());
            Dimension severity = symptom.sintomaId().startsWith("dolor:") ? Dimension.INTENSIDAD : Dimension.IMPACTO;
            if (!symptom.fiebre()) {
                added |= ensure(new Clave(symptom.sintomaId(), severity), symptom.etiqueta());
            }
            if (symptom.fiebre()) added |= ensure(new Clave(symptom.sintomaId(), Dimension.TEMPERATURA), symptom.etiqueta());
            return added;
        }

        private List<Hallazgo> bindGenericPainReferences(List<Hallazgo> found, String text) {
            boolean hasGenericPain = found.stream().anyMatch(h -> h.sintomaId().equals("dolor:general"));
            if (!hasGenericPain) return found;
            List<String> localizedPainIds = trackers.values().stream().map(t -> t.clave.sintomaId())
                    .filter(id -> id.startsWith("dolor:") && !id.equals("dolor:general")).distinct().toList();
            if (localizedPainIds.size() != 1) return found;
            boolean taskTargetsPain = tarea.tipo() == TipoTarea.DATO && tarea.claves().stream()
                    .anyMatch(key -> key.sintomaId().startsWith("dolor:") && !key.sintomaId().equals("dolor:general"));
            boolean measurementOrUpdate = painValueSpontaneous(text) != null
                    || (extractTime(text) != null && text.matches(".*\\b(?:el dolor|ese dolor|dolor)\\b.*"))
                    || text.matches(".*\\b(?:sigue|continua|se mantiene|va empeorando)\\b.*");
            if (!taskTargetsPain && !measurementOrUpdate) return found;
            String existingId = localizedPainIds.get(0);
            Tracker tracker = trackers.values().stream().filter(t -> t.clave.sintomaId().equals(existingId))
                    .findFirst().orElse(null);
            if (tracker == null) return found;
            Map<String, Hallazgo> rebound = new LinkedHashMap<>();
            for (Hallazgo finding : found) {
                if (finding.sintomaId().equals("dolor:general")) {
                    String boundSegment = finding.segmento().replaceAll("\\bdolor\\b", Matcher.quoteReplacement(tracker.etiqueta));
                    finding = new Hallazgo(existingId, tracker.etiqueta, false,
                            finding.inicio(), finding.fin(), boundSegment);
                }
                rebound.putIfAbsent(finding.sintomaId(), finding);
            }
            return List.copyOf(rebound.values());
        }

        private boolean ensure(Clave key, String label) {
            if (trackers.containsKey(key)) return false;
            trackers.put(key, new Tracker(key, label));
            return true;
        }

        private boolean uniqueSymptomReference(String text, String symptomId) {
            if (uniqueSymptom(text, symptomId)) return true;
            if (!symptomId.startsWith("dolor:") || !uniqueSymptom(text, "dolor:general")) return false;
            List<String> localizedPainIds = trackers.keySet().stream().map(Clave::sintomaId)
                    .filter(id -> id.startsWith("dolor:") && !id.equals("dolor:general"))
                    .distinct().toList();
            return localizedPainIds.size() == 1 && localizedPainIds.contains(symptomId);
        }

        private boolean applySpontaneous(Hallazgo symptom, String text) {
            boolean changed = false;
            Tracker onset = trackers.get(new Clave(symptom.sintomaId(), Dimension.INICIO));
            String segment = symptom.segmento();
            boolean uniqueReference = uniqueSymptomReference(text, symptom.sintomaId());
            boolean askedThisOnset = tarea.tipo() == TipoTarea.DATO
                    && tarea.claves().contains(new Clave(symptom.sintomaId(), Dimension.INICIO));
            String time = askedThisOnset ? extractTimeForOnset(segment) : extractTime(segment);
            if (time == null && uniqueReference) {
                time = askedThisOnset ? extractTimeForOnset(text) : extractTime(text);
            }
            if (time == null && explicitlySharedOnset(text, symptom.sintomaId())) {
                time = askedThisOnset ? extractTimeForOnset(text) : extractTime(text);
            }
            if (onset != null && onset.estado != Estado.RESPONDIDO && time != null) {
                onset.set(Estado.RESPONDIDO, time);
                changed = true;
            } else if (onset != null && onset.estado == Estado.PENDIENTE && onsetUnknownApplies(text, segment, symptom.sintomaId())) {
                onset.set(Estado.DESCONOCIDO, null);
                changed = true;
            } else if (onset != null && onset.estado == Estado.PENDIENTE && onsetRefusalApplies(text, segment, symptom.sintomaId())) {
                onset.set(Estado.RECHAZADO, null);
                changed = true;
            }
            Dimension severityDimension = symptom.sintomaId().startsWith("dolor:") ? Dimension.INTENSIDAD : Dimension.IMPACTO;
            Tracker severity = trackers.get(new Clave(symptom.sintomaId(), severityDimension));
            if (severity != null && severity.estado != Estado.RESPONDIDO) {
                String correlatedText = uniqueReference ? text : segment;
                String functionalImpact = impactValueSpontaneous(correlatedText);
                if (severity.dimension == Dimension.INTENSIDAD && functionalImpact != null
                        && (containsSymptomInSegment(text, symptom.sintomaId()) || uniqueReference
                            || uniquePain(text, symptom.sintomaId()))) {
                    severity.dimension = Dimension.IMPACTO;
                }
                String severityValue = severity.dimension == Dimension.INTENSIDAD
                        ? painValueSpontaneous(uniqueReference
                                || (uniquePain(text, symptom.sintomaId()) && hasExplicitPainScale(text)) ? text : segment)
                        : impactValueSpontaneous(correlatedText);
                if (severityValue != null && (containsSymptomInSegment(segment, symptom.sintomaId())
                        || uniqueReference)) {
                    severity.set(Estado.RESPONDIDO, severityValue);
                    changed = true;
                } else if (severityUnknownApplies(text, segment, symptom.sintomaId())) {
                    severity.set(Estado.DESCONOCIDO, null);
                    changed = true;
                } else if (severityRefusalApplies(text, segment, symptom.sintomaId())) {
                    severity.set(Estado.RECHAZADO, null);
                    changed = true;
                }
            }
            if (symptom.fiebre()) {
                Tracker temp = trackers.get(new Clave(symptom.sintomaId(), Dimension.TEMPERATURA));
                boolean uniqueFever = uniqueSymptom(text, symptom.sintomaId());
                String temperature = extractTemperature(segment, false);
                if (temperature == null && uniqueFever) temperature = extractTemperature(text, false);
                if (temp != null && temp.estado != Estado.RESPONDIDO && temperature != null) {
                    temp.set(Estado.RESPONDIDO, temperature);
                    changed = true;
                }
                if (temp != null && temp.estado == Estado.PENDIENTE && UNKNOWN.matcher(uniqueFever ? text : segment).find()
                        && (segment.contains("temperatura") || (uniqueFever && text.contains("temperatura")))) {
                    temp.set(Estado.DESCONOCIDO, null);
                    changed = true;
                } else if (temp != null && temp.estado == Estado.PENDIENTE && REFUSAL.matcher(uniqueFever ? text : segment).find()
                        && (segment.contains("temperatura") || (uniqueFever && text.contains("temperatura")))) {
                    temp.set(Estado.RECHAZADO, null);
                    changed = true;
                }
            }
            return changed;
        }

        private boolean applyUnpromptedUnknown(String text) {
            if (!UNKNOWN.matcher(text).find()) return false;
            boolean changed = false;
            boolean onsetUnknown = text.matches(".*\\b(?:desde cuando|cuando empezo|cuando comenzo|cuando comenzaron|hace cuanto|si fue|si empezo)\\b.*");
            if (onsetUnknown) {
                List<Tracker> pending = trackers.values().stream()
                        .filter(t -> t.estado == Estado.PENDIENTE && t.dimension == Dimension.INICIO).toList();
                if (pending.size() == 1) {
                    pending.get(0).set(Estado.DESCONOCIDO, null);
                    changed = true;
                }
            }
            if (text.contains("temperatura") || text.contains("no medi") || text.contains("no la medi")) {
                List<Tracker> pending = trackers.values().stream()
                        .filter(t -> t.estado == Estado.PENDIENTE && t.dimension == Dimension.TEMPERATURA).toList();
                if (pending.size() == 1) {
                    pending.get(0).set(Estado.DESCONOCIDO, null);
                    changed = true;
                }
            }
            if (text.matches(".*\\b(?:intensidad|cuanto te duele|que tanto)\\b.*")) {
                List<Tracker> pending = trackers.values().stream()
                        .filter(t -> t.estado == Estado.PENDIENTE && t.dimension == Dimension.INTENSIDAD).toList();
                if (pending.size() == 1) {
                    pending.get(0).set(Estado.DESCONOCIDO, null);
                    changed = true;
                }
            }
            return changed;
        }

        private boolean applyUnpromptedUniquePainScale(String text) {
            Matcher value = Pattern.compile("(?i)^\\s*es\\s+(10|[0-9])\\s+de\\s+10[.!]?\\s*$").matcher(text);
            if (!value.matches()) {
                value = Pattern.compile("(?i)\\bintensidad(?:\\s+del\\s+dolor)?\\s*(?:es|de|:)?\\s*(10|[0-9])(?:\\s*(?:/|de|sobre)\\s*10)?\\b").matcher(text);
                if (!value.find()) return false;
            }
            List<String> painIds = trackers.values().stream().map(t -> t.clave.sintomaId())
                    .filter(id -> id.startsWith("dolor:"))
                    .distinct().toList();
            if (painIds.size() != 1) return false;
            Tracker intensity = trackers.get(new Clave(painIds.get(0), Dimension.INTENSIDAD));
            if (intensity == null || intensity.estado != Estado.PENDIENTE) return false;
            intensity.set(Estado.RESPONDIDO, value.group(1));
            return true;
        }

        private void invalidAnswer(Tracker target) {
            if (target.intentos >= MAX_INTENTOS_POR_DATO) {
                target.set(Estado.AGOTADO, null);
                requiereAclaracion = false;
            } else {
                requiereAclaracion = true;
            }
        }

        private ResultadoRespuesta answer(Tracker target, String text) {
            List<String> mentioned = symptoms(text).stream().map(Hallazgo::sintomaId).toList();
            boolean genericPainForPainTarget = mentioned.contains("dolor:general")
                    && target.clave.sintomaId().startsWith("dolor:");
            boolean explicitlyMentionsOther = !mentioned.isEmpty()
                    && !mentioned.contains(target.clave.sintomaId())
                    && !genericPainForPainTarget
                    && !text.matches(".*\\b(?:ambos|ambas|los dos|las dos)\\b.*");
            if (explicitlyMentionsOther) return new ResultadoRespuesta(null, null);
            // A hedge about precision must not erase a relative onset affirmed in the same
            // answer. A bare unknown still resolves the requested datum as unknown.
            String onset = target.dimension == Dimension.INICIO ? extractTimeForOnset(text) : null;
            if (onset != null) return new ResultadoRespuesta(Estado.RESPONDIDO, onset);
            if (UNKNOWN.matcher(text).find()) return new ResultadoRespuesta(Estado.DESCONOCIDO, null);
            if (target.dimension == Dimension.TEMPERATURA && isNegativeMeasurementConfirmation(text)) {
                return new ResultadoRespuesta(Estado.DESCONOCIDO, null);
            }
            if (REFUSAL.matcher(text).find()) return new ResultadoRespuesta(Estado.RECHAZADO, null);
            String value = switch (target.dimension) {
                case INICIO -> extractTimeForOnset(text);
                case INTENSIDAD -> painValueForAskedScale(text);
                case IMPACTO -> impactValue(text, tarea.contenidoBot());
                case TEMPERATURA -> extractTemperature(text, true);
            };
            return value == null ? new ResultadoRespuesta(null, null) : new ResultadoRespuesta(Estado.RESPONDIDO, value);
        }

        private MotivoCierre determineReason() {
            if (alarma) return MotivoCierre.ALARMA;
            if (stop) return MotivoCierre.SOLICITUD_PACIENTE;
            if (totalRespuestas >= MAX_TURNOS) return MotivoCierre.LIMITE_RECURSOS;
            if (invitacionAporteNoClaro) return MotivoCierre.LIMITE_RECURSOS;
            if (totalRespuestas >= MAX_SIN_AVANCE && noAvance >= MAX_SIN_AVANCE) return MotivoCierre.SIN_AVANCE;
            if (!motivoClinico && !emocional) return MotivoCierre.EN_CURSO;
            boolean allTerminal = trackers.values().stream().allMatch(t -> t.estado != Estado.PENDIENTE);
            if (!allTerminal) return MotivoCierre.EN_CURSO;
            if (yaDijoQueNoAgrega) return agotado() ? MotivoCierre.LIMITE_RECURSOS : MotivoCierre.DATOS_RESUELTOS;
            if (!invitacionAbierta) return MotivoCierre.EN_CURSO;
            if (!respondioInvitacion) return MotivoCierre.EN_CURSO;
            return agotado() ? MotivoCierre.LIMITE_RECURSOS : MotivoCierre.DATOS_RESUELTOS;
        }

        private boolean agotado() { return trackers.values().stream().anyMatch(t -> t.estado == Estado.AGOTADO); }

        private SeleccionObjetivo seleccionarObjetivo(MotivoCierre reason) {
            if (reason != MotivoCierre.EN_CURSO) return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.CERRAR, null, null), null, 0, false);
            if (requiereAclaracion) {
                Tarea aclaracion = tareaAclarar == null ? tarea : tareaAclarar;
                Clave key = aclaracion.claves().stream().findFirst().orElse(null);
                Tracker target = key == null ? null : trackers.get(key);
                return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.ACLARACION,
                        key == null ? null : key.sintomaId(),
                        target == null ? (key == null ? null : key.dimension()) : target.dimension),
                        target == null ? null : target.etiqueta, target == null ? 0 : target.intentos,
                        target != null && target.medicionConfirmada);
            }
            if (tarea.tipo() == TipoTarea.DATO || tarea.tipo() == TipoTarea.CONFIRMACION_MEDICION
                    || tarea.tipo() == TipoTarea.INVITACION || tarea.tipo() == TipoTarea.ACLARACION) {
                return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.CERRAR, null, null), null, 0, false);
            }
            if (!motivoClinico && !emocional) return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.ACLARACION, null, null), null, 0, false);
            Tracker next = siguienteTrackerPendiente();
            if (next != null) return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.DATO,
                    next.clave.sintomaId(), next.dimension), next.etiqueta, next.intentos, next.medicionConfirmada);
            if (yaDijoQueNoAgrega || (invitacionAbierta && respondioInvitacion)) {
                return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.CERRAR, null, null), null, 0, false);
            }
            return new SeleccionObjetivo(new ObjetivoTurno(TipoObjetivo.INVITACION, null, null), null, 0, false);
        }

        private Tracker siguienteTrackerPendiente() {
            return trackers.values().stream().filter(t -> t.estado == Estado.PENDIENTE)
                    .min(java.util.Comparator.comparingInt(t -> dimensionPriority(t.dimension))).orElse(null);
        }

        private String instruction(MotivoCierre reason, SeleccionObjetivo selection) {
            if (reason == MotivoCierre.ALARMA) return "La entrevista requiere cierre inmediato por una alarma literal afirmada; conserva solo los hechos del paciente.";
            if (reason == MotivoCierre.SOLICITUD_PACIENTE) return "La persona pidió terminar; cierra la entrevista y conserva los datos desconocidos sin completarlos.";
            if (reason == MotivoCierre.LIMITE_RECURSOS && invitacionAporteNoClaro) {
                return "Cierra como entrevista parcial: el aporte adicional siguió sin ser claro tras una aclaración. Conserva literalmente el aporte y los pendientes para la evaluación final; no inventes ni descartes síntomas.";
            }
            if (reason == MotivoCierre.LIMITE_RECURSOS) return "Cierra como entrevista parcial por límite de recursos. Conserva pendientes y valores desconocidos; no los declares resueltos.";
            if (reason == MotivoCierre.SIN_AVANCE) return "Cierra como entrevista parcial por falta de avance. Conserva pendientes; no repitas la pregunta ni inventes un valor.";
            if (reason == MotivoCierre.DATOS_RESUELTOS) return "Los objetivos de entrevista están terminales y la invitación única fue contestada; se puede cerrar.";
            Tarea aclaracion = tareaAclarar == null ? tarea : tareaAclarar;
            if (requiereAclaracion && aclaracion.tipo() == TipoTarea.DATO && !aclaracion.claves().isEmpty()) {
                Tracker target = trackers.get(aclaracion.claves().get(0));
                if (target != null && target.estado == Estado.PENDIENTE) {
                    return "La respuesta no aportó un valor reconocible para " + target.etiqueta + " (" + target.dimension
                            + "). Pide una aclaración concreta de ese dato; conserva el objetivo pendiente.";
                }
            }
            if (requiereAclaracion && tareaAclarar == null && invitacionAbierta && !respondioInvitacion) {
                return "La respuesta a la invitación no fue clara. Pide una sola aclaración abierta y breve sobre qué más le ocurre, sin sugerir síntomas ni inventar datos.";
            }
            if (tarea.tipo() == TipoTarea.DATO) return "Espera la respuesta al único dato pendiente preguntado; no avances a otro objetivo en este turno.";
            if (tarea.tipo() == TipoTarea.CONFIRMACION_MEDICION) return "Espera la respuesta a si pudo medirse la temperatura; una confirmación no sustituye el valor numérico.";
            if (tarea.tipo() == TipoTarea.INVITACION) return "Espera la respuesta a la única invitación abierta; no vuelvas a preguntar por síntomas nuevos.";
            if (tarea.tipo() == TipoTarea.ACLARACION) return "Espera la aclaración del paciente; no infieras una sensación física ni una causa emocional.";
            if (!motivoClinico && !emocional) return "Pide una aclaración abierta sobre qué le ocurre; no enumeres síntomas ni conviertas una frase incoherente en síntoma.";
            if (selection.objetivo().tipo() == TipoObjetivo.DATO) {
                if (selection.intentos() >= MAX_INTENTOS_POR_DATO) return "No insistas en el dato agotado. Continúa con el próximo objetivo pendiente y conserva este valor como desconocido.";
                return switch (selection.objetivo().dimension()) {
                    case INICIO -> "Pregunta desde cuándo empezó " + selection.etiqueta() + "; no transfieras el inicio de otro síntoma.";
                    case INTENSIDAD -> "Pregunta en un turno la intensidad del dolor" + (selection.etiqueta().startsWith("dolor ") ? " " + selection.etiqueta().substring("dolor ".length()) : "") + "; solo registra una escala explícitamente referida a dolor.";
                    case IMPACTO -> "Pregunta en un turno el impacto funcional de " + selection.etiqueta() + "; una respuesta a otro síntoma no resuelve este dato.";
                    case TEMPERATURA -> "Pregunta cuántos grados de temperatura marcó el termómetro; acepta que no se midió o que se desconoce, y no infieras un valor.";
                };
            }
            if (yaDijoQueNoAgrega) return "No explores síntomas nuevos; cierra con la información y los desconocidos disponibles.";
            if (!invitacionAbierta) return "Haz una sola invitación breve para agregar algo sobre lo que ya le pasa; no sugieras síntomas nuevos.";
            return "Espera la respuesta a la invitación única.";
        }
    }

    private record ResultadoRespuesta(Estado estado, String valor) { }

    private static Dimension askedDimension(String text) {
        if (text.contains("desde cuando") || text.contains("cuando empezo") || text.contains("cuando empezaron")
                || text.contains("cuando comenzo") || text.contains("cuando comenzaron")
                || text.contains("hace cuanto") || text.contains("hace cuantos") || text.contains("hace cuantas")
                || text.contains("inicio") || text.contains("duracion")
                || Pattern.compile("\\bcuant[oa]s?\\s+(?:horas?|dias?|semanas?|meses?|anos?)\\b.{0,60}\\b(?:hace|empezo|comenzo|inicio|aparecio)\\b")
                        .matcher(text).find()
                || Pattern.compile("\\bcuant[oa]\\s+tiempo\\s+(?:hace|lleva|paso)\\b").matcher(text).find()) return Dimension.INICIO;
        if (text.contains("temperatura") || text.contains("cuantos grados") || text.contains("marco el termometro")
                || text.contains("cuanto marco") || text.contains("que marco el termometro")
                || PREGUNTA_VALOR_TERMOMETRO_ANTES.matcher(text).find()
                || PREGUNTA_VALOR_TERMOMETRO_DESPUES.matcher(text).find()) return Dimension.TEMPERATURA;
        if (text.contains("intensidad") && (text.contains("vacio") || text.contains("sensacion"))) return Dimension.IMPACTO;
        // When one question offers functional impact as an alternative, an explicit
        // pain-severity question still owns a numeric answer such as "8".
        if (Pattern.compile("\\bque tan (?:fuerte|intens[oa])\\b").matcher(text).find()
                && Pattern.compile("\\b(?:dolor|doli\\w*|duele(?:n)?|cefalea)\\b").matcher(text).find()) {
            return Dimension.INTENSIDAD;
        }
        if (text.contains("impacto") || text.contains("te impide") || text.contains("limita") || text.contains("funcional")
                || text.contains("afecta") || text.contains("actividades") || text.contains("hacer tus cosas")
                || esPreguntaDeImpactoFuncional(text)) return Dimension.IMPACTO;
        if (text.contains("intensidad") || text.contains("cuanto te duele") || text.contains("0 al 10")
                || text.contains("1 al 10") || text.contains("escala")) return Dimension.INTENSIDAD;
        return null;
    }

    private static boolean esPreguntaDeImpactoFuncional(String text) {
        boolean verboImpacto = text.contains("te molesta") || text.contains("te cuesta") || text.contains("te dificulta");
        boolean actividad = Pattern.compile("\\b(?:caminar|trabajar|dormir|comer|moverte|levantarte|hacer tus actividades|actividades diarias)\\b")
                .matcher(text).find();
        return verboImpacto && actividad && !text.contains("respirar");
    }

    private static boolean isMeasurementConfirmationQuestion(String text) {
        boolean explicitlyAsksForValue = text.matches("(?s).*\\b(?:que|cuantos|cuantas|cuanto|cual|valor exacto)\\b.{0,40}\\b(?:grados?|temperatura|termometro|medicion)\\b.*")
                || text.matches("(?s).*\\b(?:grados?|temperatura|termometro|medicion)\\b.{0,35}\\b(?:marco|valor|cuantos|cuanto)\\b.*");
        if (explicitlyAsksForValue) return false;
        boolean asksWhetherMeasured = text.matches("(?s).*\\b(?:pudiste|lograste|llegaste a)\\s+medir(?:te)?\\b.*")
                || text.matches("(?s).*\\b(?:pudiste|lograste|llegaste a)\\s+tomar(?:te)?\\b.*")
                || text.matches("(?s).*\\bte\\s+mediste\\b.*")
                || text.matches("(?s).*\\bmediste\\b.*")
                || text.matches("(?s).*\\b(?:te\\s+)?tomaste\\b.*");
        return asksWhetherMeasured && (text.contains("temperatura") || text.contains("termometro"));
    }

    private static boolean isPositiveMeasurementConfirmation(String text) {
        if (isNegativeMeasurementConfirmation(text)) return false;
        return text.matches("^(?:si|claro|correcto|exacto)[.!]?$")
                || text.matches("(?s).*\\b(?:si|claro|correcto|exacto)\\b.{0,35}\\b(?:(?:me\\s+)?(?:la\\s+)?(?:medi|medido|medimos|tome|tomado))\\b.*")
                || text.matches("(?s).*\\bme\\s+(?:la\\s+)?(?:medi|tome|tomado)\\b.*")
                || text.matches("(?s).*\\b(?:la\\s+)?(?:medi|tome|tomado)\\b.*");
    }

    private static boolean isNegativeMeasurementConfirmation(String text) {
        return text.matches("(?s).*\\bno\\s+(?:tengo|hay|consegui|consigo)\\s+(?:un\\s+)?termometro\\b.*")
                || text.matches("(?s).*\\bno\\s+(?:me\\s+)?(?:la\\s+)?(?:medi|tome|tomado)\\b.*")
                || text.matches("(?s).*\\bno\\s+(?:pude|pudimos|logre)\\s+(?:medir|medirme|tomar|tomarme)\\b.*");
    }

    private static int dimensionPriority(Dimension dimension) {
        return switch (dimension) {
            case INICIO -> 0;
            case INTENSIDAD, IMPACTO -> 1;
            case TEMPERATURA -> 2;
        };
    }

    private static List<Hallazgo> symptoms(String text) {
        Map<String, Hallazgo> found = new LinkedHashMap<>();
        Matcher separators = Pattern.compile("[,;.!?\\n]|\\bpero\\b|\\baunque\\b|\\by\\b").matcher(text);
        int segmentStart = 0;
        while (true) {
            boolean hasSeparator = separators.find();
            int segmentEnd = hasSeparator ? separators.start() : text.length();
            String segment = text.substring(segmentStart, segmentEnd);
            Matcher pain = LOCATION_PAIN.matcher(segment);
            while (pain.find()) {
                if (!affirmedAt(text, segmentStart + pain.start())) continue;
                String site = pain.group(1);
                String id = "dolor:" + canonicalPainSite(site);
                found.putIfAbsent(id, new Hallazgo(id, "dolor de " + site, false, pain.start(), pain.end(), segment));
            }
            Matcher genericPain = Pattern.compile("\\b(?:cefalea|dolor(?:es)?)\\b").matcher(segment);
            while (genericPain.find()) {
                if (!affirmedAt(text, segmentStart + genericPain.start())) continue;
                boolean covered = found.values().stream().anyMatch(h -> h.inicio() <= genericPain.start() && h.fin() >= genericPain.end());
                if (!covered) {
                    boolean cefalea = genericPain.group().equals("cefalea");
                    String id = cefalea ? "dolor:cabeza" : "dolor:general";
                    String label = cefalea ? "dolor de cabeza" : "el dolor";
                    found.putIfAbsent(id, new Hallazgo(id, label, false, genericPain.start(), genericPain.end(), segment));
                }
            }
            for (SymptomSpec spec : SYMPTOMS) {
                if (spec.id().equals("traumatismo")
                        && segment.matches(".*\\b(?:moraton(?:es)?|moreton(?:es)?|hematoma(?:s)?)\\b.*\\b(?:por|tras|despues de)\\s+(?:un\\s+)?(?:golpe|torcedura)\\b.*")) continue;
                Matcher matcher = Pattern.compile(spec.regex()).matcher(segment);
                while (matcher.find()) {
                    if (!affirmedAt(text, segmentStart + matcher.start())) continue;
                    String id = spec.id().equals("fiebre") ? "fiebre" : "sintoma:" + spec.id();
                    found.putIfAbsent(id, new Hallazgo(id, spec.label(), spec.fever(), matcher.start(), matcher.end(), segment));
                }
            }
            Matcher vacuum = Pattern.compile("\\b(?:vacio|vacia)\\b").matcher(segment);
            while (vacuum.find()) {
                boolean afirmacionFisica = affirmatedPattern(segment,
                        Pattern.compile("\\b(?:fisic[oa]|fisicamente|corporal)\\b"));
                boolean autodescriptiva = segment.matches(".*\\bsensacion\\b.*") && afirmacionFisica;
                if (!affirmedAt(text, segmentStart + vacuum.start())
                        || !(segment.matches(".*\\b(?:pecho|cuerpo)\\b.*") && afirmacionFisica || autodescriptiva)) continue;
                String id = "sensacion:vacio_pecho";
                found.putIfAbsent(id, new Hallazgo(id, "la sensación de vacío", false, vacuum.start(), vacuum.end(), segment));
            }
            if (!hasSeparator) break;
            segmentStart = separators.end();
        }
        Matcher pairedPain = PAIRED_LOCATION_PAIN.matcher(text);
        while (pairedPain.find()) {
            int secondLocationStart = pairedPain.start(1);
            if (!affirmedAt(text, secondLocationStart)) continue;
            String site = pairedPain.group(1);
            String id = "dolor:" + canonicalPainSite(site);
            String remainder = text.substring(pairedPain.end(1));
            found.putIfAbsent(id, new Hallazgo(id, "dolor de " + site, false,
                    secondLocationStart, pairedPain.end(1), remainder));
        }
        return List.copyOf(found.values());
    }

    private static String canonicalPainSite(String site) {
        return switch (site) {
            case "panza", "barriga" -> "abdomen";
            default -> site;
        };
    }

    private static boolean affirmed(String text, int start) {
        String prefix = text.substring(0, start);
        if (ORDEN_INVENTAR.matcher(prefix).find()) return false;
        Matcher negations = NEGACION.matcher(prefix);
        int last = -1;
        while (negations.find()) last = negations.start();
        if (last >= 0) {
            String afterLastNegation = prefix.substring(last);
            if (afterLastNegation.matches("(?s)^no\\s+se\\s+si\\s+[^,;.!?]*$|^no\\s+recuerdo\\s+(?:si|haber)\\s+[^,;.!?]*$")) return false;
        }
        return last < 0 || AFIRMACION.matcher(prefix.substring(last)).find();
    }

    private static boolean affirmedAt(String text, int start) {
        int sentenceStart = Math.max(Math.max(text.lastIndexOf('.', start - 1), text.lastIndexOf('!', start - 1)),
                Math.max(Math.max(Math.max(text.lastIndexOf('?', start - 1), text.lastIndexOf(';', start - 1)),
                        text.lastIndexOf('\n', start - 1)), -1)) + 1;
        String sentence = text.substring(sentenceStart);
        return affirmed(sentence, start - sentenceStart);
    }

    private static boolean affirmatedPattern(String text, Pattern pattern) {
        for (String segment : text.split("[,;.!?\\n]|\\bpero\\b|\\baunque\\b|\\by\\b")) {
            Matcher matcher = pattern.matcher(segment);
            while (matcher.find()) if (affirmed(segment, matcher.start())) return true;
        }
        return false;
    }

    private static boolean containsSymptomInSegment(String text, String id) {
        return symptoms(text).stream().anyMatch(s -> s.sintomaId().equals(id));
    }

    private static boolean explicitlySharedOnset(String text, String symptomId) {
        return text.matches(".*\\b(?:ambos|ambas|los dos|las dos)\\b.*")
                && symptoms(text).stream().map(Hallazgo::sintomaId).distinct().count() == 2
                && symptoms(text).stream().anyMatch(s -> s.sintomaId().equals(symptomId));
    }

    private static String extractTime(String text) {
        Matcher m = FECHA.matcher(text);
        if (m.find()) {
            Matcher tramoDia = FECHA_TRAMO_DIA.matcher(text);
            tramoDia.region(m.end(), text.length());
            return tramoDia.lookingAt() ? text.substring(m.start(), tramoDia.end()).strip() : m.group();
        }
        Matcher duration = Pattern.compile("\\b\\d+\\s*(?:minutos?|horas?|dias?|semanas?|meses?|anos?)\\b").matcher(text);
        return duration.find() ? duration.group() : null;
    }

    private static String extractTimeForOnset(String text) {
        Matcher relative = INICIO_RELATIVO.matcher(text);
        if (relative.find()) return affirmedAt(text, relative.start()) ? relative.group().strip() : null;
        Matcher compound = DURACION_COMPUESTA.matcher(text);
        if (compound.find()) return affirmedAt(text, compound.start()) ? compound.group() : null;
        String time = extractTime(text);
        if (time == null) return null;
        Matcher candidate = Pattern.compile(Pattern.quote(time)).matcher(text);
        return candidate.find() && affirmedAt(text, candidate.start()) ? time : null;
    }

    private static String painValue(String text) {
        String value = text.strip();
        Matcher fraction = Pattern.compile("^(?:intensidad(?: del dolor)?\\s*(?:es|de)?\\s*)?(10|[0-9])\\s*(?:/|de|sobre)\\s*10[.!]?$|^(?:intensidad(?: del dolor)?\\s*(?:es|de)?\\s*)?(10|[0-9])[.!]?$", Pattern.CASE_INSENSITIVE).matcher(value);
        if (fraction.matches()) return fraction.group(1) != null ? fraction.group(1) : fraction.group(2);
        value = value.replaceFirst("^un\\s+", "");
        Matcher word = Pattern.compile("^(?:el dolor es )?(cero|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)[.!]?$", Pattern.CASE_INSENSITIVE).matcher(value);
        if (word.matches()) return word.group(1).toLowerCase(Locale.ROOT);
        Matcher qualitative = Pattern.compile("^(?:es |me duele )?(leve|moderad[oa]|fuerte|intens[oa])[.!]?$", Pattern.CASE_INSENSITIVE).matcher(value);
        return qualitative.matches() ? qualitative.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String painValueForAskedScale(String text) {
        String exact = painValue(text);
        if (exact != null) return exact;
        Matcher qualitativeAnswer = Pattern.compile("^(?:es\\s+)?(?:muy\\s+)?(?:leve|moderad[oa]|fuerte|intens[oa])[.!]?$",
                Pattern.CASE_INSENSITIVE).matcher(text.strip());
        if (qualitativeAnswer.matches()) {
            return text.strip().replaceFirst("(?i)^es\\s+", "").replaceFirst("[.!]$", "")
                    .toLowerCase(Locale.ROOT);
        }
        Matcher leading = Pattern.compile("^(?:un\\s+)?(10|[0-9]|cero|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)(?:\\s*(?:/|de|sobre)\\s*10)?(?=$|[,;.!?])").matcher(text.strip());
        return leading.find() ? leading.group(1).toLowerCase(Locale.ROOT) : painValueSpontaneous(text);
    }

    private static String painValueSpontaneous(String text) {
        String direct = painValue(text);
        if (direct != null) return direct;
        Matcher explicitScale = Pattern.compile("\\b(?:intensidad|dolor|doli\\w*|duele(?:n)?|cefalea)\\b.{0,60}?\\b(10|[0-9])\\s*(?:/|de|sobre)\\s*10\\b").matcher(text);
        if (explicitScale.find()) return explicitScale.group(1);
        Matcher explicit = Pattern.compile("\\bintensidad(?: del dolor)?\\s*(?:es|de|:)?\\s*(10|[0-9])\\b").matcher(text);
        if (explicit.find()) return explicit.group(1);
        Matcher qualitative = Pattern.compile("\\b(?:leve|moderad[oa]|fuerte|intens[oa])\\b").matcher(text);
        return qualitative.find() && Pattern.compile("\\b(?:dolor|doli\\w*|cefalea)\\b").matcher(text).find()
                ? qualitative.group() : null;
    }

    private static boolean hasExplicitPainScale(String text) {
        return Pattern.compile("\\b(?:intensidad|dolor|doli\\w*|duele(?:n)?|cefalea)\\b.{0,60}?\\b(10|[0-9])\\s*(?:/|de|sobre)\\s*10\\b")
                .matcher(text).find();
    }

    private static String impactValueSpontaneous(String text) {
        String value = impactValue(text);
        if (value != null) return value;
        Matcher qualifier = Pattern.compile("\\b(?:mucho|muchisimo|bastante|poco|muy poco|nada|un poco)\\b").matcher(text);
        if (qualifier.find() && Pattern.compile("\\b(?:me cuesta|me resulta dificil|me impide|me limita|me molesta|me afecta|me dificulta|no puedo)\\b")
                .matcher(text).find()) return qualifier.group();
        Matcher functional = Pattern.compile("\\b(?:no puedo|me cuesta|me resulta dificil|no logro|me impide|me limita)\\b.{0,60}").matcher(text);
        return functional.find() ? functional.group().strip() : null;
    }

    private static boolean uniqueSymptom(String text, String symptomId) {
        List<String> ids = symptoms(text).stream().map(Hallazgo::sintomaId).distinct().toList();
        return ids.size() == 1 && ids.contains(symptomId);
    }

    private static boolean uniquePain(String text, String symptomId) {
        List<String> ids = symptoms(text).stream().map(Hallazgo::sintomaId).filter(id -> id.startsWith("dolor:")).distinct().toList();
        return ids.size() == 1 && ids.contains(symptomId);
    }

    private static boolean onsetUnknownApplies(String text, String segment, String symptomId) {
        String scoped = uniqueSymptom(text, symptomId) ? text : segment;
        return UNKNOWN.matcher(scoped).find() && askedDimension(scoped) == Dimension.INICIO;
    }

    private static boolean severityUnknownApplies(String text, String segment, String symptomId) {
        String scoped = uniqueSymptom(text, symptomId) ? text : segment;
        return UNKNOWN.matcher(scoped).find()
                && scoped.matches(".*\\b(?:intensidad|cuanto te duele|que tanto)\\b.*");
    }

    private static boolean severityRefusalApplies(String text, String segment, String symptomId) {
        String scoped = uniqueSymptom(text, symptomId) ? text : segment;
        return REFUSAL.matcher(scoped).find()
                && scoped.matches(".*\\b(?:intensidad|cuanto te duele|que tanto)\\b.*");
    }

    private static boolean onsetRefusalApplies(String text, String segment, String symptomId) {
        String scoped = uniqueSymptom(text, symptomId) ? text : segment;
        return REFUSAL.matcher(scoped).find() && askedDimension(scoped) == Dimension.INICIO;
    }

    private static String impactValue(String text) {
        if (UNKNOWN.matcher(text).find()) return null;
        if (REFUSAL.matcher(text).find()) return null;
        if (IMPACTO_CUALIFICADO_FUNCIONAL.matcher(text.strip()).matches()) return text.strip();
        if (text.matches("^(?:no|no me limita|no me impide|nada|no[, ]+no me limita|no[, ]+no me impide)[.!]?$")) return "sin impacto";
        if (text.matches("^si[, ]+(?:me )?(?:limita|impide|cuesta|resulta dificil|no puedo)\\b.*[.!]?$")) return text.strip();
        if (text.matches("^(?:si|mucho|muchisimo|bastante|poco|muy poco|un poco|me cuesta .+|no puedo .+)[.!]?$")) return text.strip();
        return null;
    }

    private static String impactValue(String text, String question) {
        String value = impactValue(text);
        if (value != null) return value;
        // A bare scale is usable only when the question explicitly attached it to a
        // non-pain sensation or functional impact, never as an implicit pain rating.
        if (question != null && !question.contains("dolor") && !question.contains("duele")
                && (question.contains("intensidad") || question.contains("escala") || question.contains("0 al 10")
                    || question.contains("1 al 10"))) {
            Matcher scale = Pattern.compile("^(10|[0-9])(?:\\s*(?:/|de|sobre)\\s*10)?[.!]?$|^(cero|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)[.!]?$").matcher(text.strip());
            if (scale.matches()) return scale.group();
        }
        return null;
    }

    private static String extractTemperature(String text, boolean allowBareValue) {
        if (text.matches(".*\\b(?:kg|kilos?|cm|milimetros?|metros?|fahrenheit)\\b.*")
                || text.matches("(?s).*\\b\\d{2}(?:[.,]\\d)?\\s*°\\s*f(?:ahrenheit)?\\b.*")) return null;
        Matcher measurement = MEDICION.matcher(text);
        String value = null;
        while (measurement.find()) {
            if (!affirmedAt(text, measurement.start()) || esMedicionHipotetica(text, measurement.start())) continue;
            String measured = (measurement.group(1) != null ? measurement.group(1) : measurement.group(2))
                    .replace(',', '.');
            if (measurement.group().matches("(?s).*\\s+y\\s+medio\\b.*")) {
                if (measured.contains(".")) return null;
                measured += ".5";
            }
            if (value != null && !value.equals(measured)) return null;
            value = measured;
        }
        if (value != null) return value;
        if (!allowBareValue) return null;
        Matcher bare = TEMPERATURA_BARE.matcher(text.strip());
        return bare.matches() ? (bare.group(1) != null ? bare.group(1) : bare.group(2)).replace(',', '.') : null;
    }

    private static boolean esMedicionHipotetica(String text, int start) {
        String prefix = text.substring(0, start).stripTrailing();
        boolean hypotheticalMood = prefix.matches("(?s).*\\bsi\\s+(?:tuviera|tuviese|fuera|estuviera|llegara a tener)\\b.*$");
        boolean conditionalQuestion = text.matches("(?s).*\\bsi\\s+(?:tengo|me|siento|presento)\\b.{0,60}\\b(?:que hago|que debo hacer|que deberia hacer|que puedo hacer|que pasa)\\b.*");
        return hypotheticalMood || conditionalQuestion;
    }

    private static boolean physicalAffirmation(String text) {
        return affirmatedPattern(text, Pattern.compile("\\b(?:fisic[oa]|fisicamente|corporal|en el cuerpo|del cuerpo)\\b"));
    }

    private static boolean priorVacuum(List<Turno> history) {
        boolean reported = false;
        for (Turno turn : history) {
            if (turn == null || turn.excluido() || turn.autor() != AutorMensaje.PACIENTE) continue;
            String text = norm(turn.contenido());
            for (String segment : text.split("[,;.!?\\n]|\\bpero\\b|\\baunque\\b|\\by\\b")) {
                if (segment.matches(".*\\b(?:vacio|vacia)\\b.*\\b(?:pecho|cuerpo)\\b.*")
                        && !segment.matches(".*\\b(?:no|nunca|sin)\\b.{0,30}\\b(?:vacio|vacia)\\b.*")) {
                    reported = true;
                }
            }
        }
        return reported;
    }

    private static boolean isNoMoreSymptoms(String text) {
        return text.matches(".*\\b(?:no tengo (?:nada mas|mas sintomas|otros sintomas)|no hay (?:nada mas|otros sintomas)|nada mas que agregar|no quiero (?:agregar|seguir|contar)|no deseo (?:agregar|seguir|contar))\\b.*");
    }

    private static boolean esDeclinacionInvitacion(String text) {
        // STT commonly punctuates a single negative answer as "No. Nada." or "No, no.".
        // Match the whole reply so an additional complaint is never discarded as courtesy.
        return isNoMoreSymptoms(text) || text.matches(
                "^(?:no|nada(?: mas)?|gracias)(?:[\\s,;.!]+(?:no|nada(?: mas)?|gracias))*[.! ]*$") || text.matches(
                "^(?:no|no,? gracias|no,?\\s+nada mas|no se|no recuerdo|desconozco|nada|nada mas|gracias|muchas gracias|eso es todo|prefiero no agregar(?: nada)?|no quiero agregar(?: nada)?|no deseo agregar(?: nada)?)[.! ]*$");
    }

    private static boolean stopSolicitado(String text) {
        return java.util.Arrays.stream(text.split("[,;.!?\\n]"))
                .map(String::strip)
                .anyMatch(segment -> STOP.matcher(segment).matches());
    }

    private static boolean exploratoryAnswer(String question, String answer) {
        if (question == null || question.isBlank()) return false;
        boolean safetyOrHistoryQuestion = Pattern.compile("\\b(?:respirar|respiratoria|falta de aire|dolor de pecho|toracico|confusion|desmayo|alergias?|medicacion|antecedentes)\\b")
                .matcher(question).find();
        return safetyOrHistoryQuestion && Pattern.compile("^(?:no|ningun[oa]s?|no se|no recuerdo|prefiero no responder|no quiero responder)(?:[,; ].*)?[.!]?$")
                .matcher(answer).matches();
    }

    private static String norm(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }

    /** Ignore role-prefixed quoted/injected lines as patient evidence, but keep the original history untouched. */
    private static String patientEvidence(String text) {
        return java.util.Arrays.stream(text.split("\\R"))
                .filter(line -> !line.matches("^\\s*(?:bot|assistant|system|developer|gemini|chatgpt)\\s*[:：].*$"))
                .reduce((left, right) -> left + "\\n" + right).orElse("").strip();
    }
}
