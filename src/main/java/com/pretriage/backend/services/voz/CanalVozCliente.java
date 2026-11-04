package com.pretriage.backend.services.voz;

/**
 * Canal hacia el cliente (navegador/app) de una sesion de voz.
 */
public interface CanalVozCliente {

    /** Evento JSON de control o transcripcion. */
    void enviarEvento(String eventoJson);

    /** Audio PCM 16 bits mono little-endian a 24 kHz generado por Gemini. */
    void enviarAudio(byte[] audioPcm);

    void cerrar();
}
