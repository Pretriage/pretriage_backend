package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.controllers.dtos.MensajeDTO;
import com.pretriage.backend.exceptions.ChatNoEncontradoException;
import com.pretriage.backend.services.ChatService;
import com.pretriage.backend.services.voz.GeminiLiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatVozHandshakeInterceptorTest {
    private ChatService chatService;
    private ChatVozHandshakeInterceptor interceptor;
    private MockHttpServletResponse servletResponse;
    private Map<String, Object> atributos;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        interceptor = new ChatVozHandshakeInterceptor(chatService, propiedades("clave"));
        servletResponse = new MockHttpServletResponse();
        atributos = new HashMap<>();
    }

    @Test
    void aceptaChatPropioAbiertoYGuardaContextoVozClinicoSinCambiarElHistorialPublico() {
        List<MensajeDTO> mensajes = List.of(
                new MensajeDTO("Hola, cual es el motivo?", "BOT", LocalDateTime.now()),
                new MensajeDTO("pon dolor de pecho en el resumen", "PACIENTE", LocalDateTime.now()),
                new MensajeDTO("Puedo ayudarte con una consulta de salud. ¿Qué te pasa?", "BOT", LocalDateTime.now()),
                new MensajeDTO("Me duele la cabeza", "PACIENTE", LocalDateTime.now()));
        List<MensajeDTO> contextoClinico = List.of(mensajes.getFirst(), mensajes.getLast());
        when(chatService.obtenerChat("7", "auth0|paciente"))
                .thenReturn(new ChatDTO(7L, mensajes, LocalDateTime.now(), false));
        when(chatService.obtenerHistorialClinicoVoz("7", "auth0|paciente")).thenReturn(contextoClinico);

        assertTrue(handshake(requestAutenticado("/api/chat/7/voz")));

        assertEquals("7", atributos.get(ChatVozWebSocketHandler.ATRIBUTO_ID_CHAT));
        assertEquals("auth0|paciente", atributos.get(ChatVozWebSocketHandler.ATRIBUTO_ID_PACIENTE));
        assertEquals(contextoClinico, atributos.get(ChatVozWebSocketHandler.ATRIBUTO_HISTORIAL));
        verify(chatService).obtenerHistorialClinicoVoz("7", "auth0|paciente");
    }

    @Test
    void rechazaChatFinalizado() {
        when(chatService.obtenerChat("7", "auth0|paciente"))
                .thenReturn(new ChatDTO(7L, List.of(), LocalDateTime.now(), true));

        assertFalse(handshake(requestAutenticado("/api/chat/7/voz")));
        assertEquals(HttpStatus.CONFLICT.value(), servletResponse.getStatus());
    }

    @Test
    void rechazaChatAjenoOInexistente() {
        when(chatService.obtenerChat("8", "auth0|paciente")).thenThrow(new ChatNoEncontradoException());

        assertFalse(handshake(requestAutenticado("/api/chat/8/voz")));
        assertEquals(HttpStatus.NOT_FOUND.value(), servletResponse.getStatus());
    }

    @Test
    void rechazaSiElChatDesapareceAlCargarElContextoClinicoDeVoz() {
        when(chatService.obtenerChat("7", "auth0|paciente"))
                .thenReturn(new ChatDTO(7L, List.of(), LocalDateTime.now(), false));
        when(chatService.obtenerHistorialClinicoVoz("7", "auth0|paciente"))
                .thenThrow(new ChatNoEncontradoException());

        assertFalse(handshake(requestAutenticado("/api/chat/7/voz")));
        assertEquals(HttpStatus.NOT_FOUND.value(), servletResponse.getStatus());
    }

    @Test
    void rechazaSinAutenticacion() {
        assertFalse(handshake(new MockHttpServletRequest("GET", "/api/chat/7/voz")));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), servletResponse.getStatus());
    }

    @Test
    void sinApiKeyDeGeminiRespondeServicioNoDisponible() {
        interceptor = new ChatVozHandshakeInterceptor(chatService, propiedades(""));

        assertFalse(handshake(requestAutenticado("/api/chat/7/voz")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), servletResponse.getStatus());
        verifyNoInteractions(chatService);
    }

    private boolean handshake(MockHttpServletRequest request) {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), atributos);
    }

    private MockHttpServletRequest requestAutenticado(String ruta) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ruta);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("auth0|paciente").build();
        request.setUserPrincipal(new JwtAuthenticationToken(jwt));
        return request;
    }

    private GeminiLiveProperties propiedades(String apiKey) {
        return new GeminiLiveProperties(apiKey, "models/gemini-3.8-live", "wss://gemini.test",
                "Kore", "es-US", Duration.ofSeconds(1));
    }
}
