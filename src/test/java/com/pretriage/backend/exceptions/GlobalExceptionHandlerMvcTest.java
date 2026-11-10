package com.pretriage.backend.exceptions;

import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerMvcTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RegressionController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void mapeaNoResourceFoundA404SinExponerLaRuta() throws Exception {
        mockMvc.perform(get("/regression/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Recurso no encontrado."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private/files/secret.txt"))));
    }

    @Test
    void devuelveJson404AunqueElClienteSoliciteSse() throws Exception {
        mockMvc.perform(get("/regression/missing").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Recurso no encontrado."));
    }

    @Test
    void mapeaTipoDeContenidoNoSoportadoA415DuranteElDispatchMvc() throws Exception {
        MvcResult result = mockMvc.perform(post("/regression/json")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Tipo de contenido no soportado."))
                .andReturn();

        String accept = result.getResponse().getHeader(HttpHeaders.ACCEPT);
        org.junit.jupiter.api.Assertions.assertTrue(accept != null
                && accept.contains(MediaType.APPLICATION_JSON_VALUE));
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.springframework.web.HttpMediaTypeNotSupportedException.class,
                result.getResolvedException());
    }

    @Test
    void conservaBadRequestParaRuntimeExceptionEsperada() throws Exception {
        mockMvc.perform(get("/regression/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Entrada inválida."));
    }

    @Test
    void conservaForbiddenYConflictDeHandlersEspecificos() throws Exception {
        mockMvc.perform(get("/regression/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Sin permiso."));

        mockMvc.perform(get("/regression/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflicto."));
    }

    @Test
    void informaCodigoYAlcanceDeUnaSancionDigital() throws Exception {
        mockMvc.perform(get("/regression/patient-sanctioned"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("PATIENT_SANCTIONED"))
                .andExpect(jsonPath("$.vigente").value(true))
                .andExpect(jsonPath("$.permanente").value(false))
                .andExpect(jsonPath("$.fechaFin").value("2027-05-09T17:00:00Z"))
                .andExpect(jsonPath("$.avisoId").value("sancion-1"));
    }

    @Test
    void conservaLockedParaCambioDeContraseniaBloqueado() throws Exception {
        mockMvc.perform(get("/regression/locked"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value(
                        "El cambio de contraseña está bloqueado por intentos fallidos. Podrás intentarlo nuevamente en 5 minuto(s)."));
    }

    @Test
    void convierteFalloInesperadoA500Generico() throws Exception {
        mockMvc.perform(get("/regression/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(
                        "Ocurrió un error inesperado. Intentá nuevamente más tarde."));
    }

    @Test
    void todoExceptionHandlerAceptaSuTipoDeclaradoEnElParametro() {
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            org.springframework.web.bind.annotation.ExceptionHandler annotation =
                    method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (annotation == null) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            for (Class<? extends Throwable> exceptionType : annotation.value()) {
                org.junit.jupiter.api.Assertions.assertTrue(parameterType.isAssignableFrom(exceptionType),
                        () -> method.getName() + " recibe " + parameterType.getSimpleName()
                                + " pero declara " + exceptionType.getSimpleName());
            }
        }
    }

    @RestController
    static class RegressionController {
        @GetMapping("/regression/missing")
        Map<String, String> missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/regression/missing", "private/files/secret.txt");
        }

        @PostMapping(value = "/regression/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> json(@RequestBody Map<String, Object> body) {
            return Map.of("ok", "true");
        }

        @GetMapping("/regression/bad-request")
        Map<String, String> badRequest() {
            throw new IllegalArgumentException("Entrada inválida.");
        }

        @GetMapping("/regression/forbidden")
        Map<String, String> forbidden() {
            throw new org.springframework.security.access.AccessDeniedException("Sin permiso.");
        }

        @GetMapping("/regression/patient-sanctioned")
        void patientSanctioned() {
            throw new PacienteSancionadoException(new EstadoSancionPacienteDTO(true, false,
                    Instant.parse("2027-05-09T17:00:00Z"), "sancion-1", null));
        }

        @GetMapping("/regression/conflict")
        Map<String, String> conflict() {
            throw new ConflictoDeEstadoException("Conflicto.");
        }

        @GetMapping("/regression/locked")
        Map<String, String> locked() {
            throw new CambioContraseniaBloqueadoException(5);
        }

        @GetMapping("/regression/unexpected")
        Map<String, String> unexpected() throws Exception {
            throw new Exception("internal database path");
        }
    }
}
