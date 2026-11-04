package com.pretriage.backend.controllers;

import com.pretriage.backend.config.SpringSecurityConfig;
import com.pretriage.backend.exceptions.CambioContraseniaBloqueadoException;
import com.pretriage.backend.exceptions.ContraseniaInvalidaException;
import com.pretriage.backend.services.CambioContraseniaAutenticadoService;
import com.pretriage.backend.services.CambioContraseniaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CambioContraseniaController.class)
@Import(SpringSecurityConfig.class)
class CambioContraseniaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CambioContraseniaService cambioContraseniaService;

    @MockitoBean
    private CambioContraseniaAutenticadoService cambioContraseniaAutenticadoService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String BODY_VALIDO = """
            {"contraseniaActual":"Actual123!","contraseniaNueva":"Nueva123!"}
            """;

    private void stubJwtValido() {
        Jwt jwt = Jwt.withTokenValue("token-valido")
                .header("alg", "RS256")
                .subject("auth0|usuario-1")
                .build();
        when(jwtDecoder.decode("token-valido")).thenReturn(jwt);
    }

    // --- 4.1: éxito y fracaso ---

    @Test
    void cambioAutenticadoExitoso_retorna200ConMensaje() throws Exception {
        stubJwtValido();
        doNothing().when(cambioContraseniaAutenticadoService)
                .cambiarContrasenia(eq("auth0|usuario-1"), any());

        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .header("Authorization", "Bearer token-valido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Contraseña cambiada con éxito"));
    }

    @Test
    void contraseniaNuevaIgualALaActual_retorna400ConMensajeExplicativo() throws Exception {
        stubJwtValido();
        doThrow(new ContraseniaInvalidaException("La contraseña nueva debe ser distinta de la contraseña actual"))
                .when(cambioContraseniaAutenticadoService)
                .cambiarContrasenia(eq("auth0|usuario-1"), any());

        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .header("Authorization", "Bearer token-valido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("La contraseña nueva debe ser distinta de la contraseña actual"));
    }

    @Test
    void contraseniaNuevaDebil_retorna400SinInvocarAlServicio() throws Exception {
        stubJwtValido();

        // sin mayúscula, sin minúscula y sin número ni especial, y muy corta
        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .header("Authorization", "Bearer token-valido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contraseniaActual\":\"Actual123!\",\"contraseniaNueva\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                // errores de Bean Validation: contrato de mapa campo->mensaje (docs/06-api-reference.md)
                .andExpect(jsonPath("$.contraseniaNueva").exists());

        // @Valid la rechaza antes de la lógica de cambio: no cuenta como intento fallido
        verify(cambioContraseniaAutenticadoService, never()).cambiarContrasenia(anyString(), any());
    }

    @Test
    void cambioBloqueado_retorna423ConMinutos() throws Exception {
        stubJwtValido();
        doThrow(new CambioContraseniaBloqueadoException(42))
                .when(cambioContraseniaAutenticadoService)
                .cambiarContrasenia(eq("auth0|usuario-1"), any());

        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .header("Authorization", "Bearer token-valido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("42")));
    }

    // --- 4.2: seguridad ---

    @Test
    void sinJwt_retorna401() throws Exception {
        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conJwtInvalido_retorna401() throws Exception {
        when(jwtDecoder.decode("token-invalido"))
                .thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("malformed"));

        mockMvc.perform(post("/api/auth/cambio-contrasenia/actual")
                        .header("Authorization", "Bearer token-invalido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());

        verify(cambioContraseniaAutenticadoService, never()).cambiarContrasenia(anyString(), any());
    }
}
