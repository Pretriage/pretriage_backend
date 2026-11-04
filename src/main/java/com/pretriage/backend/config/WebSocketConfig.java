package com.pretriage.backend.config;

import com.pretriage.backend.controllers.ChatVozHandshakeInterceptor;
import com.pretriage.backend.controllers.ChatVozWebSocketHandler;
import com.pretriage.backend.services.voz.GeminiLiveProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(GeminiLiveProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    // Un frame de audio del cliente (PCM 16 kHz) no deberia superar unos segundos.
    private static final int MAXIMO_FRAME_BYTES = 256 * 1024;
    private static final long INACTIVIDAD_MAXIMA_MS = 5 * 60 * 1000L;

    private final ChatVozWebSocketHandler chatVozWebSocketHandler;
    private final ChatVozHandshakeInterceptor chatVozHandshakeInterceptor;
    private final String[] origenesPermitidos;

    public WebSocketConfig(ChatVozWebSocketHandler chatVozWebSocketHandler,
                           ChatVozHandshakeInterceptor chatVozHandshakeInterceptor,
                           @Value("${pretriage.voz.origenes-permitidos:*}") String[] origenesPermitidos) {
        this.chatVozWebSocketHandler = chatVozWebSocketHandler;
        this.chatVozHandshakeInterceptor = chatVozHandshakeInterceptor;
        this.origenesPermitidos = origenesPermitidos;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatVozWebSocketHandler, "/api/chat/*/voz")
                .addInterceptors(chatVozHandshakeInterceptor)
                .setAllowedOriginPatterns(origenesPermitidos);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAXIMO_FRAME_BYTES);
        container.setMaxTextMessageBufferSize(16 * 1024);
        container.setMaxSessionIdleTimeout(INACTIVIDAD_MAXIMA_MS);
        return container;
    }
}
