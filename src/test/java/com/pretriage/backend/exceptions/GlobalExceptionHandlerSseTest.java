package com.pretriage.backend.exceptions;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerSseTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void clienteDesconectadoEnSseNoIntentaEscribirUnaRespuesta() throws Exception {
        mockMvc.perform(get("/sse-disconnect").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void clienteDesconectadoTrasComprometerLaRespuestaSseNoAgregaCuerpoNiCambiaEstado() throws Exception {
        mockMvc.perform(get("/sse-disconnect-committed").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""))
                .andExpect(result -> assertTrue(result.getResponse().isCommitted()));
    }

    @Test
    void erroresHttpNormalesSiguenUsandoSusRespuestasJson() throws Exception {
        mockMvc.perform(get("/bad-request").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"bad request\"}"));

        mockMvc.perform(get("/server-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        "{\"error\":\"Ocurrió un error inesperado. Intentá nuevamente más tarde.\"}"));
    }

    @RestController
    static class TestController {
        @GetMapping(value = "/sse-disconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnectedSse(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
        }

        @GetMapping(value = "/sse-disconnect-committed", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnectedCommittedSse(HttpServletResponse response) throws Exception {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.flushBuffer();
            throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
        }

        @GetMapping("/bad-request")
        void badRequest() {
            throw new IllegalArgumentException("bad request");
        }

        @GetMapping("/server-error")
        void serverError() {
            throw new RuntimeException("internal detail");
        }
    }
}
