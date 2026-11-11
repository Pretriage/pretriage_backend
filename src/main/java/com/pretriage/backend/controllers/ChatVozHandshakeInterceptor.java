package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.ChatDTO;
import com.pretriage.backend.controllers.dtos.MensajeDTO;
import com.pretriage.backend.exceptions.ChatNoEncontradoException;
import com.pretriage.backend.services.ChatService;
import com.pretriage.backend.model.chat.EstadoTurnoTexto;
import com.pretriage.backend.services.voz.GeminiLiveProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida el handshake del chat de voz: paciente autenticado, chat propio y abierto,
 * y proveedor de voz configurado.
 */
@Component
public class ChatVozHandshakeInterceptor implements HandshakeInterceptor {
    private static final Pattern RUTA_VOZ = Pattern.compile("/api/chat/([^/]+)/voz/?$");

    private final ChatService chatService;
    private final GeminiLiveProperties properties;

    public ChatVozHandshakeInterceptor(ChatService chatService, GeminiLiveProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!(request.getPrincipal() instanceof JwtAuthenticationToken autenticacion)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!properties.configurado()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        Matcher matcher = RUTA_VOZ.matcher(request.getURI().getPath());
        if (!matcher.find()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        String idChat = matcher.group(1);
        String idPaciente = autenticacion.getToken().getSubject();
        ChatDTO chat;
        try {
            chat = chatService.obtenerChat(idChat, idPaciente);
        } catch (ChatNoEncontradoException exception) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (chat.finalizado() || chat.estadoTurnoTexto() == EstadoTurnoTexto.PENDIENTE
                || chat.estadoTurnoTexto() == EstadoTurnoTexto.NO_DISPONIBLE
                || chatService.atencionIniciada(idChat, idPaciente)) {
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }

        List<MensajeDTO> historialClinico;
        try {
            historialClinico = chatService.obtenerHistorialClinicoVoz(idChat, idPaciente);
        } catch (ChatNoEncontradoException exception) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        attributes.put(ChatVozWebSocketHandler.ATRIBUTO_ID_CHAT, idChat);
        attributes.put(ChatVozWebSocketHandler.ATRIBUTO_ID_PACIENTE, idPaciente);
        // Keep the public chat transcript untouched; voice receives only context
        // that was accepted for triage (rejected patient turns remain visible in REST).
        attributes.put(ChatVozWebSocketHandler.ATRIBUTO_HISTORIAL, historialClinico);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
