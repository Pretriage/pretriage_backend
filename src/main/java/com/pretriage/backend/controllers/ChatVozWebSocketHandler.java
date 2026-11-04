package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.MensajeDTO;
import com.pretriage.backend.services.ChatService;
import com.pretriage.backend.services.voz.CanalVozCliente;
import com.pretriage.backend.services.voz.GeminiLiveCliente;
import com.pretriage.backend.services.voz.GeminiLiveProperties;
import com.pretriage.backend.services.voz.SesionVozChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket {@code /api/chat/{id}/voz}: el cliente envia audio PCM 16 kHz en frames binarios
 * y eventos de control JSON; recibe audio PCM 24 kHz y eventos JSON del chat.
 */
@Component
public class ChatVozWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatVozWebSocketHandler.class);

    static final String ATRIBUTO_ID_CHAT = "idChat";
    static final String ATRIBUTO_ID_PACIENTE = "idPaciente";
    static final String ATRIBUTO_HISTORIAL = "historial";

    private static final int LIMITE_ENVIO_MS = 10_000;
    private static final int LIMITE_BUFFER_BYTES = 2 * 1024 * 1024;

    private final ChatService chatService;
    private final GeminiLiveCliente geminiLiveCliente;
    private final GeminiLiveProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, SesionVozChat> sesiones = new ConcurrentHashMap<>();

    public ChatVozWebSocketHandler(ChatService chatService,
                                   GeminiLiveCliente geminiLiveCliente,
                                   GeminiLiveProperties properties,
                                   ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.geminiLiveCliente = geminiLiveCliente;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(
                session, LIMITE_ENVIO_MS, LIMITE_BUFFER_BYTES);
        SesionVozChat sesionVoz = new SesionVozChat(
                (String) session.getAttributes().get(ATRIBUTO_ID_CHAT),
                (String) session.getAttributes().get(ATRIBUTO_ID_PACIENTE),
                historial(session),
                chatService,
                geminiLiveCliente,
                properties,
                objectMapper,
                new CanalWebSocket(segura));
        sesiones.put(session.getId(), sesionVoz);
        sesionVoz.iniciar();
    }

    @SuppressWarnings("unchecked")
    private List<MensajeDTO> historial(WebSocketSession session) {
        Object historial = session.getAttributes().get(ATRIBUTO_HISTORIAL);
        return historial instanceof List<?> mensajes ? (List<MensajeDTO>) mensajes : List.of();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SesionVozChat sesionVoz = sesiones.get(session.getId());
        if (sesionVoz != null) {
            ByteBuffer payload = message.getPayload();
            byte[] audio = new byte[payload.remaining()];
            payload.get(audio);
            sesionVoz.recibirAudioCliente(audio);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SesionVozChat sesionVoz = sesiones.get(session.getId());
        if (sesionVoz != null) {
            sesionVoz.recibirControlCliente(message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SesionVozChat sesionVoz = sesiones.remove(session.getId());
        if (sesionVoz != null) {
            sesionVoz.cerrar();
        }
    }

    private record CanalWebSocket(WebSocketSession session) implements CanalVozCliente {

        @Override
        public void enviarEvento(String eventoJson) {
            enviar(new TextMessage(eventoJson));
        }

        @Override
        public void enviarAudio(byte[] audioPcm) {
            enviar(new BinaryMessage(audioPcm));
        }

        @Override
        public void cerrar() {
            if (session.isOpen()) {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (IOException exception) {
                    log.debug("No se pudo cerrar el WebSocket de voz", exception);
                }
            }
        }

        private void enviar(org.springframework.web.socket.WebSocketMessage<?> mensaje) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(mensaje);
            } catch (IOException | RuntimeException exception) {
                log.debug("No se pudo enviar al WebSocket de voz", exception);
            }
        }
    }
}
