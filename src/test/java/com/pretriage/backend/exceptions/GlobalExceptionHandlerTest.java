package com.pretriage.backend.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void devuelveForbiddenParaFaltaDePermisos() {
        assertEquals(HttpStatus.FORBIDDEN,
                handler.handleAccessDenied(new AccessDeniedException("sin permiso")).getStatusCode());
    }

    @Test
    void devuelveNotFoundParaRecursoInexistente() {
        assertEquals(HttpStatus.NOT_FOUND,
                handler.handleNotFound(new RecursoNoEncontradoException("inexistente")).getStatusCode());
    }

    @Test
    void devuelveConflictParaTransicionInvalida() {
        assertEquals(HttpStatus.CONFLICT,
                handler.handleStateConflict(new ConflictoDeEstadoException("conflicto")).getStatusCode());
    }

    @Test
    void devuelveUnauthorizedParaRefreshTokenInvalido() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleRefreshTokenInvalido(new RefreshTokenInvalidoException()).getStatusCode());
    }

    @Test
    void devuelveBadRequestParaOtrosErroresAuth() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleExceptions(new NoSePudoCrearUsuario("error")).getStatusCode());
    }

    @Test
    void devuelveBadRequestConMensajeParaAtencionPendiente() {
        var respuesta = handler.handleExceptions(new AtencionPendienteException());
        assertEquals(HttpStatus.BAD_REQUEST, respuesta.getStatusCode());
        assertEquals(new AtencionPendienteException().getMessage(), respuesta.getBody().get("error"));
    }

    @Test
    void devuelveLockedConMinutosRestantesParaCambioContraseniaBloqueado() {
        var respuesta = handler.handleCambioContraseniaBloqueado(new CambioContraseniaBloqueadoException(27));

        assertEquals(HttpStatus.LOCKED, respuesta.getStatusCode());
        String mensaje = respuesta.getBody().get("error");
        assertTrue(mensaje.contains("27"));
        assertTrue(mensaje.contains("bloqueado"));
    }

    @Test
    void devuelveBadRequestConMapaDeCamposParaContraseniaInvalida() {
        var respuesta = handler.handleExceptions(
                new ContraseniaInvalidaException( "La contraseña nueva debe ser distinta de la contraseña actual"));

        assertEquals(HttpStatus.BAD_REQUEST, respuesta.getStatusCode());
        assertEquals("La contraseña nueva debe ser distinta de la contraseña actual",
                respuesta.getBody().get("error"));
    }

    @Test
    void devuelveErrorInternoGenericoParaUsuarioLocalInexistente() {
        var respuesta = handler.handleInternalServerErrors(new UsuarioLocalNoExisteException("auth0|no-existe"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, respuesta.getStatusCode());
        String mensaje = respuesta.getBody().get("error");
        assertFalse(mensaje.contains("auth0|no-existe"));
        assertTrue(mensaje.contains("Intentá nuevamente más tarde"));
    }
}
