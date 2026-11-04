package com.pretriage.backend.services.voz;

/**
 * Recibe los eventos de una conexion Gemini Live.
 */
public interface GeminiLiveOyente {

    /** Mensaje JSON completo enviado por el servidor de Gemini. */
    void alRecibir(String mensajeJson);

    void alCerrar(int codigo, String motivo);

    void alFallar(Throwable error);
}
