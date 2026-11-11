package com.pretriage.backend.services.voz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;

/**
 * Configuracion del proveedor de voz Gemini Live ({@code pretriage.voz.gemini.*}).
 *
 * <p>Los parametros de deteccion de voz estan pensados para guardias ruidosas: hace falta voz
 * clara para que Gemini considere que el paciente empezo a hablar, se toleran pausas al
 * describir sintomas y el ruido no corta al asistente mientras habla.
 */
@ConfigurationProperties(prefix = "pretriage.voz.gemini")
public record GeminiLiveProperties(
        String apiKey,
        String modelo,
        String url,
        String voz,
        /** Idioma de la transcripcion de lo que dice el paciente (BCP-47). Vacio = no se envia. */
        String idioma,
        Duration connectTimeout,
        /** START_SENSITIVITY_LOW: el ruido de fondo dispara menos detecciones falsas. */
        String sensibilidadInicioVoz,
        /** END_SENSITIVITY_LOW: no corta el turno ante pausas breves. */
        String sensibilidadFinVoz,
        /** Audio previo al inicio detectado que se conserva, para no perder la primera palabra. */
        Integer margenPrevioMs,
        /** Silencio necesario para dar por terminado el turno del paciente. */
        Integer silencioFinTurnoMs,
        /** NO_INTERRUPTION: un ruido no interrumpe al asistente; START_OF_ACTIVITY_INTERRUPTS si se quiere. */
        String manejoActividad,
        /** Terminos que la transcripcion debe reconocer mejor (sintomas, medicamentos). Vacio = no se envia. */
        List<String> vocabulario) {

    static final String SENSIBILIDAD_INICIO_POR_DEFECTO = "START_SENSITIVITY_LOW";
    static final String SENSIBILIDAD_FIN_POR_DEFECTO = "END_SENSITIVITY_LOW";
    static final int MARGEN_PREVIO_POR_DEFECTO_MS = 300;
    static final int SILENCIO_FIN_TURNO_POR_DEFECTO_MS = 900;
    static final String MANEJO_ACTIVIDAD_POR_DEFECTO = "NO_INTERRUPTION";

    static final List<String> VOCABULARIO_POR_DEFECTO = List.of(
            "fiebre", "febrícula", "dolor de cabeza", "cefalea", "mareo", "vértigo", "náuseas", "vómitos",
            "diarrea", "dolor abdominal", "dolor de pecho", "palpitaciones", "falta de aire", "tos",
            "dolor de garganta", "congestión", "convulsión", "desmayo", "hormigueo", "sangrado",
            "presión alta", "presión baja", "hipertensión", "diabetes", "asma", "alergia", "embarazada",
            "ibuprofeno", "paracetamol", "amoxicilina", "insulina", "enalapril", "losartán", "omeprazol",
            "salbutamol", "anticoagulante", "obra social", "prepaga");

    @ConstructorBinding
    public GeminiLiveProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (sensibilidadInicioVoz == null || sensibilidadInicioVoz.isBlank()) {
            sensibilidadInicioVoz = SENSIBILIDAD_INICIO_POR_DEFECTO;
        }
        if (sensibilidadFinVoz == null || sensibilidadFinVoz.isBlank()) {
            sensibilidadFinVoz = SENSIBILIDAD_FIN_POR_DEFECTO;
        }
        if (margenPrevioMs == null || margenPrevioMs < 0) {
            margenPrevioMs = MARGEN_PREVIO_POR_DEFECTO_MS;
        }
        if (silencioFinTurnoMs == null || silencioFinTurnoMs <= 0) {
            silencioFinTurnoMs = SILENCIO_FIN_TURNO_POR_DEFECTO_MS;
        }
        if (manejoActividad == null || manejoActividad.isBlank()) {
            manejoActividad = MANEJO_ACTIVIDAD_POR_DEFECTO;
        }
        vocabulario = vocabulario == null ? VOCABULARIO_POR_DEFECTO
                : vocabulario.stream().filter(termino -> termino != null && !termino.isBlank()).toList();
    }

    /** Mantiene la forma anterior: la deteccion de voz usa los valores por defecto. */
    public GeminiLiveProperties(String apiKey, String modelo, String url, String voz, String idioma,
                                Duration connectTimeout) {
        this(apiKey, modelo, url, voz, idioma, connectTimeout, null, null, null, null, null, null);
    }

    public boolean configurado() {
        return apiKey != null && !apiKey.isBlank()
                && modelo != null && !modelo.isBlank()
                && url != null && !url.isBlank();
    }
}
