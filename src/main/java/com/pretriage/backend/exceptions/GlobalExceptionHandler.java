package com.pretriage.backend.exceptions;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException e) {
        // The response is already unusable; writing an error payload would only trigger another failure.
        log.debug("Cliente desconectado durante respuesta asincrónica: {}", e.getMessage());
    }

    @ExceptionHandler({ CredencialValidaYaExisteException.class, CredencialInvalidaException.class,
            ObraSocialSinValidadorException.class, PacienteNoExisteException.class,
            NoSePudoCrearUsuario.class, NoSePudoEstimarElHorarioDeAtencion.class, ChatFinalizadoException.class,
            NoSePudoObtenerHospital.class, ObraSocialYaExisteException.class, ObraSocialNoExisteException.class,
            RecepcionistaNoExisteException.class, AtencionPendienteException.class,
            NoSuchElementException.class, IllegalStateException.class, IllegalArgumentException.class,
            TokenCambioContraseniaInvalidoException.class, NoSePudoCambiarContraseniaException.class,
            ConstraintViolationException.class, ChatNoEncontradoException.class, ContraseniaInvalidaException.class })
    public ResponseEntity<Map<String, String>> handleExceptions(
            RuntimeException e) {

        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "Recurso no encontrado."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(e.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "Tipo de contenido no soportado."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({ ConflictoDeEstadoException.class, AtencionEnCursoException.class })
    public ResponseEntity<Map<String, String>> handleStateConflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({ Exception.class, ProveedorIaException.class, ArchivoS3Exception.class, UsuarioLocalNoExisteException.class })
    public ResponseEntity<Map<String, String>> handleInternalServerErrors(Exception e) {
        log.error("ocurrio un error inesperado( {} ): {}", e.getClass(), e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Ocurrió un error inesperado. Intentá nuevamente más tarde."));
    }

    @ExceptionHandler(RefreshTokenInvalidoException.class)
    public ResponseEntity<Map<String, String>> handleRefreshTokenInvalido(RefreshTokenInvalidoException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LimiteSolicitudesCambioContraseniaException.class)
    public ResponseEntity<Map<String, String>> handleLimiteSolicitudes(LimiteSolicitudesCambioContraseniaException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CambioContraseniaBloqueadoException.class)
    public ResponseEntity<Map<String, String>> handleCambioContraseniaBloqueado(
            CambioContraseniaBloqueadoException e) {
        // 423 Locked: el recurso (la opción de cambiar la contraseña) está bloqueado.
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentNotValidException e) {

        Map<String, String> errores = new HashMap<>();

        e.getBindingResult().getFieldErrors()
                .forEach(error -> errores.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest().body(errores);
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class })
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentTypeMismatchException e) {
        String parametro = e.getName();
        String tipo = e.getRequiredType().getSimpleName();
        String errorMessage;
        if (tipo.equals("LocalDate")) {
            errorMessage = "El parámetro: '" + parametro + "' debe ser del tipo: '"
                    + tipo + "' con el formato: 'YYYY-MM-DD'";
        } else {
            errorMessage = "El parámetro: '" + parametro + "' debe ser del tipo: '" + tipo + "'";
        }

        return ResponseEntity.badRequest()
                .body(Map.of("error", errorMessage));
    }
    
}
