package com.pretriage.backend.services.voz;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cliente WebSocket de la Live API de Gemini (protocolo BidiGenerateContent).
 * La API key viaja en la URL y nunca se registra en logs.
 */
@Component
public class GeminiLiveCliente {

    private final GeminiLiveProperties properties;
    private final HttpClient httpClient;

    public GeminiLiveCliente(GeminiLiveProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public CompletableFuture<GeminiLiveConexion> conectar(GeminiLiveOyente oyente) {
        URI uri = URI.create(properties.url() + "?key="
                + URLEncoder.encode(properties.apiKey(), StandardCharsets.UTF_8));
        return httpClient.newWebSocketBuilder()
                .connectTimeout(properties.connectTimeout())
                .buildAsync(uri, new Receptor(oyente))
                .thenApply(ConexionJdk::new);
    }

    private static final class ConexionJdk implements GeminiLiveConexion {
        private final WebSocket webSocket;
        private CompletableFuture<?> ultimoEnvio = CompletableFuture.completedFuture(null);

        private ConexionJdk(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        @Override
        public synchronized void enviar(String mensajeJson) {
            // WebSocket del JDK no admite envios superpuestos: se encadenan.
            ultimoEnvio = ultimoEnvio
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> webSocket.sendText(mensajeJson, true));
        }

        @Override
        public synchronized void cerrar() {
            ultimoEnvio = ultimoEnvio
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> webSocket.isOutputClosed()
                            ? CompletableFuture.completedFuture(webSocket)
                            : webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "fin"));
        }
    }

    /** Reensambla fragmentos: Gemini envia JSON tanto en frames de texto como binarios. */
    private static final class Receptor implements WebSocket.Listener {
        private final GeminiLiveOyente oyente;
        private final StringBuilder texto = new StringBuilder();
        private byte[] binario = new byte[0];

        private Receptor(GeminiLiveOyente oyente) {
            this.oyente = oyente;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            texto.append(data);
            if (last) {
                String mensaje = texto.toString();
                texto.setLength(0);
                oyente.alRecibir(mensaje);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] fragmento = new byte[data.remaining()];
            data.get(fragmento);
            byte[] acumulado = new byte[binario.length + fragmento.length];
            System.arraycopy(binario, 0, acumulado, 0, binario.length);
            System.arraycopy(fragmento, 0, acumulado, binario.length, fragmento.length);
            binario = acumulado;
            if (last) {
                String mensaje = new String(binario, StandardCharsets.UTF_8);
                binario = new byte[0];
                oyente.alRecibir(mensaje);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            oyente.alCerrar(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            oyente.alFallar(error);
        }
    }
}
