package com.pretriage.backend.controllers.dtos;

public record TriageAiResponse(
        boolean finalizado,
        String mensaje,
        TriageResultDTO resultado,
        EstadoEntradaChat estadoEntrada) {

    /** Keeps existing internal callers relevant by default. */
    public TriageAiResponse(boolean finalizado, String mensaje, TriageResultDTO resultado) {
        this(finalizado, mensaje, resultado, EstadoEntradaChat.RELEVANTE);
    }
}
