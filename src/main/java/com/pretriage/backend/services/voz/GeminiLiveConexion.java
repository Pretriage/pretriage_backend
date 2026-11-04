package com.pretriage.backend.services.voz;

/**
 * Conexion abierta con la Live API de Gemini.
 */
public interface GeminiLiveConexion {

    /** Envia un mensaje JSON del protocolo BidiGenerateContent. */
    void enviar(String mensajeJson);

    void cerrar();
}
