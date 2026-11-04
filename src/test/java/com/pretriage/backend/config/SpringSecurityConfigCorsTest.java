package com.pretriage.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringSecurityConfigCorsTest {
    private final SpringSecurityConfig config = new SpringSecurityConfig();
    private final DefaultCorsProcessor processor = new DefaultCorsProcessor();

    @Test
    void allowlistedOriginCanMakeCorsPreflightRequest() throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource(new String[] {"https://frontend.example"}, new String[] {"*"})
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/chats"));
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chats");
        request.addHeader("Origin", "https://frontend.example");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(processor.processRequest(cors, request, response));
        assertEquals("https://frontend.example", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("GET"));
    }

    @Test
    void nonAllowlistedOriginIsRejected() throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource(new String[] {"https://frontend.example"}, new String[] {"*"})
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/chats"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        request.addHeader("Origin", "https://untrusted.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(processor.processRequest(cors, request, response));
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void sameOriginStyleRequestWithoutOriginHeaderPassesCorsProcessor() throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource(new String[0], new String[] {"*"})
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/chats"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(processor.processRequest(cors, request, response));
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configuredVoiceOriginWorksWhenGeneralHttpCorsListIsEmpty() throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource(new String[0], new String[] {"https://voice.example"})
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/chat/123/voz"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/123/voz");
        request.addHeader("Origin", "https://voice.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(processor.processRequest(cors, request, response));
        assertEquals("https://voice.example", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void voiceOriginOutsideWebsocketAllowlistIsRejected() throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource(new String[0], new String[] {"https://voice.example"})
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/chat/123/voz"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/123/voz");
        request.addHeader("Origin", "https://untrusted.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(processor.processRequest(cors, request, response));
        assertEquals(403, response.getStatus());
    }
}
