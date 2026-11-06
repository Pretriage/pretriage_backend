package com.pretriage.backend.services.voz;

import com.pretriage.backend.exceptions.ProveedorIaException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiLiveClienteTest {

    @Test
    void soloErroresDeTransporteSeClasificanComoIndisponibilidad() {
        assertTrue(GeminiLiveCliente.clasificarError(new IOException()).isApiNoDisponible());
        assertTrue(GeminiLiveCliente.clasificarError(new HttpTimeoutException("timeout")).isApiNoDisponible());
        assertTrue(GeminiLiveCliente.clasificarError(new TimeoutException()).isApiNoDisponible());
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION,
                GeminiLiveCliente.clasificarError(new SecurityException()).getCategoria());
        assertEquals(ProveedorIaException.Categoria.OPERACION_INTERNA,
                GeminiLiveCliente.clasificarError(new IllegalStateException()).getCategoria());
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION,
                GeminiLiveCliente.clasificarError(new IOException("wrapper", new javax.net.ssl.SSLHandshakeException("TLS")))
                        .getCategoria());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handshake403AnidadoEnIOExceptionSigueSiendoConfiguracion() {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        var wrapped = new IOException("wrapper", new WebSocketHandshakeException(response));

        var failure = GeminiLiveCliente.clasificarError(wrapped);

        assertEquals(ProveedorIaException.Categoria.CONFIGURACION, failure.getCategoria());
        assertEquals(403, failure.getStatusHttp());
        assertFalse(failure.isApiNoDisponible());
    }

    @Test
    void configuracionLiveInvalidaFallaConProveedorTipadoSinConstruirElWebSocket() {
        GeminiLiveOyente oyente = mock(GeminiLiveOyente.class);
        HttpClient http = mock(HttpClient.class);
        for (GeminiLiveProperties properties : List.of(
                new GeminiLiveProperties(null, "models/live", "wss://gemini.test", "Kore", "es", null),
                new GeminiLiveProperties("key", "models/live", "https://gemini.test", "Kore", "es", null))) {
            GeminiLiveCliente client = new GeminiLiveCliente(properties, http);
            var future = client.conectar(oyente);
            var wrapper = assertThrows(CompletionException.class, future::join);
            assertInstanceOf(ProveedorIaException.class, wrapper.getCause());
            assertEquals(ProveedorIaException.Categoria.CONFIGURACION,
                    ((ProveedorIaException) wrapper.getCause()).getCategoria());
        }
        verifyNoInteractions(http);
    }

    @Test
    void errorJsonLiveClasificaDisponibilidadConfiguracionYValidacion() {
        assertEquals(ProveedorIaException.Categoria.API_NO_DISPONIBLE,
                GeminiLiveCliente.clasificarMensajeJson("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}")
                        .getCategoria());
        assertEquals(ProveedorIaException.Categoria.API_NO_DISPONIBLE,
                GeminiLiveCliente.clasificarMensajeJson("{\"error\":{\"code\":503}}")
                        .getCategoria());
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION,
                GeminiLiveCliente.clasificarMensajeJson("{\"error\":{\"code\":403}}")
                        .getCategoria());
        ProveedorIaException invalidTool = GeminiLiveCliente.clasificarMensajeJson(
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"private provider text\"}}");
        assertEquals(ProveedorIaException.Categoria.VALIDACION_RESPUESTA, invalidTool.getCategoria());
        assertFalse(invalidTool.getMessage().contains("private provider text"));
        assertEquals(ProveedorIaException.Categoria.VALIDACION_RESPUESTA,
                GeminiLiveCliente.clasificarMensajeJson("{not-json").getCategoria());
    }

    @Test
    void erroresHttpDeHandshakeClasificanSoloLosEstadosDeOutageComoApiNoDisponible() {
        assertEquals(ProveedorIaException.Categoria.API_NO_DISPONIBLE, GeminiLiveCliente.categoriaHttp(408));
        assertEquals(ProveedorIaException.Categoria.API_NO_DISPONIBLE, GeminiLiveCliente.categoriaHttp(429));
        assertEquals(ProveedorIaException.Categoria.API_NO_DISPONIBLE, GeminiLiveCliente.categoriaHttp(503));
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION, GeminiLiveCliente.categoriaHttp(401));
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION, GeminiLiveCliente.categoriaHttp(403));
        assertEquals(ProveedorIaException.Categoria.CONFIGURACION, GeminiLiveCliente.categoriaHttp(404));
        assertEquals(ProveedorIaException.Categoria.VALIDACION_RESPUESTA, GeminiLiveCliente.categoriaHttp(400));
    }

    @Test
    void errorJsonNoSeEntregaComoContenidoNormalAlOyente() {
        GeminiLiveOyente oyente = mock(GeminiLiveOyente.class);
        GeminiLiveCliente.Receptor receptor = new GeminiLiveCliente.Receptor(oyente);
        java.net.http.WebSocket socket = mock(java.net.http.WebSocket.class);
        receptor.onText(socket, "{\"error\":{\"code\":503}}", true);

        verify(oyente).alFallar(argThat(error -> error instanceof ProveedorIaException proveedor
                && proveedor.isApiNoDisponible()));
        verify(oyente, never()).alRecibir(anyString());
    }
}
