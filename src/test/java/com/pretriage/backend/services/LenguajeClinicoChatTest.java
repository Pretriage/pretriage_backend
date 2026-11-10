package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LenguajeClinicoChatTest {
    @Test
    void reglasDeEntrevistaCuantificanSoloSintomasAfirmadosQueSiguenPendientes() {
        assertTrue(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("solo los datos pertinentes que sigan pendientes"));
        assertTrue(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("como maximo una invitacion abierta"));
        assertFalse(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("maximo de cuatro preguntas"));
        assertTrue(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("sintomas que ya afirmo"));
        assertTrue(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("temperatura solo si el paciente la midio"));
        assertTrue(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("no quiere seguir respondiendo"));
        assertFalse(LenguajeClinicoChat.PREGUNTAS_SENCILLAS.contains("termina la entrevista en ese turno"));
    }

    @Test
    void unMotivoAmbiguoSinOtrosHechosClarosNoSeConvierteEnClasificacion() {
        String reglasResultado = normalizarPrompt(LenguajeClinicoChat.REGLAS_RESULTADO);
        String reglasEntrevista = normalizarPrompt(LenguajeClinicoChat.REGLAS_ENTREVISTA);
        assertTrue(reglasResultado.contains("si es el unico motivo informado"));
        assertTrue(reglasResultado.contains("no devuelvas un resultado"));
        assertTrue(reglasResultado.contains("solo si tambien hay otros hechos clinicos comprensibles"));
        assertTrue(reglasEntrevista.contains("si el unico motivo sigue ambiguo"));
        assertTrue(reglasEntrevista.contains("pide una aclaracion"));
    }

    @Test
    void malestarEmocionalNoExigeSintomasFisicosYMetaforasNoSeConviertenEnAlarmas() {
        String alcance = normalizarPrompt(LenguajeClinicoChat.REGLAS_ALCANCE_ENTRADA);
        String pregunta = normalizarPrompt(LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO);
        String entrevista = normalizarPrompt(LenguajeClinicoChat.REGLAS_ENTREVISTA);
        assertTrue(alcance.contains("malestar emocional y salud mental aunque no se informe un sintoma fisico"));
        assertTrue(pregunta.contains("no exijas sintomas fisicos"));
        assertTrue(entrevista.contains("una expresion ambigua como \"un vacio en el pecho\""));
        assertTrue(entrevista.contains("empieza invitando a describir con palabras propias que quiso decir o que siente"));
        assertTrue(entrevista.contains("sin ofrecer interpretaciones o categorias"));
        assertTrue(entrevista.contains("solo si la respuesta aun deja una ambiguedad relevante"));
    }

    private String normalizarPrompt(String prompt) {
        return prompt.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Test
    void canonizaVarianteRegionalSinCambiarHechosNiNombresClinicos() {
        var original = new TriageResultDTO("Moratón en rodilla izquierda",
                List.of("MORATONES", "no hay moraton en la derecha", "hormigueo"), "hace 3 días",
                "empeora", 9, List.of(), List.of("moratonia"), List.of("Moraton 20 mg"),
                List.of("Moraton"), "no informado", "Dijo literalmente: moratón", 3, false, "Evaluación presencial");
        var normalizado = LenguajeClinicoChat.normalizarResultado(original);
        assertEquals("Moretón en rodilla izquierda", normalizado.motivoConsulta());
        assertEquals(List.of("MORETONES", "no hay moretón en la derecha", "hormigueo"), normalizado.sintomas());
        assertEquals(original.inicio(), normalizado.inicio());
        assertEquals(original.evolucion(), normalizado.evolucion());
        assertEquals(original.intensidadDolor(), normalizado.intensidadDolor());
        assertEquals(original.nivelPrioridad(), normalizado.nivelPrioridad());
        assertEquals(original.requiereAtencionInmediata(), normalizado.requiereAtencionInmediata());
        assertEquals(original.antecedentesRelevantes(), normalizado.antecedentesRelevantes());
        assertEquals(original.medicamentos(), normalizado.medicamentos());
        assertEquals(original.alergias(), normalizado.alergias());
        assertEquals(original.observaciones(), normalizado.observaciones());
        assertEquals(original.recomendacionSeguridad(), normalizado.recomendacionSeguridad());
        assertEquals(normalizado, LenguajeClinicoChat.normalizarResultado(normalizado));
    }

    @Test
    void noInfiereTerminosDesconocidosNiReemplazaDentroDeOtraPalabra() {
        var original = new TriageResultDTO("flurbitis y moratonia", List.of("palabra desconocida"),
                "no informado", "no informado", null, List.of(), List.of(), List.of(), List.of(),
                "no informado", "Término no aclarado: flurbitis", 2, false, "Evaluación presencial");
        assertEquals(original, LenguajeClinicoChat.normalizarResultado(original));
    }
}
