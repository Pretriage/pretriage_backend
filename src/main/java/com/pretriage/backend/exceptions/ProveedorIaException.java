package com.pretriage.backend.exceptions;

import tools.jackson.core.JacksonException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Typed failure categories for AI-provider work; the message never includes provider or patient content. */
public class ProveedorIaException extends RuntimeException {
    public enum Categoria {
        API_NO_DISPONIBLE,
        VALIDACION_RESPUESTA,
        CONFIGURACION,
        OPERACION_INTERNA
    }

    private final Categoria categoria;
    private final Integer statusHttp;
    private final Duration retryAfter;

    /** Legacy constructor. Existing provider failures retain their category when wrapped. */
    public ProveedorIaException(Throwable cause) {
        this(cause, categoriaDe(cause), statusDe(cause), retryAfterDe(cause));
    }

    /** Legacy constructor; {@code transitorio=true} continues to mean retryable provider outage. */
    public ProveedorIaException(Throwable cause, boolean transitorio) {
        this(cause, transitorio ? Categoria.API_NO_DISPONIBLE : categoriaNoTransitoriaDe(cause),
                statusDe(cause), retryAfterDe(cause));
    }

    public ProveedorIaException(Throwable cause, Categoria categoria) {
        this(cause, categoria, null, null);
    }

    public ProveedorIaException(Throwable cause, Categoria categoria, Integer statusHttp, Duration retryAfter) {
        super("No fue posible obtener una respuesta del asistente de triage", cause);
        this.categoria = categoria == null ? Categoria.OPERACION_INTERNA : categoria;
        this.statusHttp = statusHttp;
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? null : retryAfter;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public Integer getStatusHttp() {
        return statusHttp;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isApiNoDisponible() {
        return categoria == Categoria.API_NO_DISPONIBLE;
    }

    /** Compatibility helper: only a real API outage is considered transient. */
    public boolean esTransitorio() {
        return isApiNoDisponible();
    }

    private static Categoria categoriaDe(Throwable cause) {
        for (Throwable actual : cadena(cause)) {
            if (actual instanceof ProveedorIaException proveedor) return proveedor.categoria;
        }
        for (Throwable actual : cadena(cause)) {
            if (esErrorTls(actual)) return Categoria.CONFIGURACION;
            if (actual instanceof WebSocketHandshakeException handshake) {
                int status = handshake.getResponse().statusCode();
                if (status == 408 || status == 429 || status >= 500) return Categoria.API_NO_DISPONIBLE;
                if (status == 401 || status == 403 || status == 404) return Categoria.CONFIGURACION;
                if (status >= 400) return Categoria.VALIDACION_RESPUESTA;
            }
        }
        for (Throwable actual : cadena(cause)) {
            if (actual instanceof SocketTimeoutException || actual instanceof ConnectException
                    || actual instanceof IOException || actual instanceof java.util.concurrent.TimeoutException) {
                return Categoria.API_NO_DISPONIBLE;
            }
        }
        if (contieneErrorDeRespuesta(cause)) return Categoria.VALIDACION_RESPUESTA;
        return Categoria.OPERACION_INTERNA;
    }

    private static Categoria categoriaNoTransitoriaDe(Throwable cause) {
        if (cause instanceof ProveedorIaException proveedor && !proveedor.isApiNoDisponible()) {
            return proveedor.categoria;
        }
        return categoriaDe(cause) == Categoria.API_NO_DISPONIBLE
                ? Categoria.OPERACION_INTERNA : categoriaDe(cause);
    }

    private static Integer statusDe(Throwable cause) {
        for (Throwable actual : cadena(cause)) {
            if (actual instanceof ProveedorIaException proveedor && proveedor.statusHttp != null) return proveedor.statusHttp;
            if (actual instanceof WebSocketHandshakeException handshake) return handshake.getResponse().statusCode();
        }
        return null;
    }

    private static Duration retryAfterDe(Throwable cause) {
        for (Throwable actual : cadena(cause)) {
            if (actual instanceof ProveedorIaException proveedor && proveedor.retryAfter != null) return proveedor.retryAfter;
            if (actual instanceof WebSocketHandshakeException handshake) {
                try {
                    String value = handshake.getResponse().headers().firstValue("Retry-After").orElse(null);
                    if (value != null) {
                        try {
                            long seconds = Long.parseLong(value.trim());
                            if (seconds >= 0) return Duration.ofSeconds(seconds);
                        } catch (NumberFormatException ignored) {
                            var retryAt = java.time.ZonedDateTime.parse(value,
                                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                            Duration delay = Duration.between(java.time.Instant.now(), retryAt);
                            return delay.isNegative() ? Duration.ZERO : delay;
                        }
                    }
                } catch (RuntimeException ignored) { return null; }
            }
        }
        return null;
    }

    private static boolean contieneErrorDeRespuesta(Throwable cause) {
        Set<Throwable> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable actual = cause; actual != null && vistos.add(actual); actual = actual.getCause()) {
            if (actual instanceof JacksonException || actual.getClass().getSimpleName().equals("InvalidResponseException")) {
                return true;
            }
        }
        return false;
    }

    private static boolean esErrorTls(Throwable error) {
        return error instanceof SSLException || error instanceof CertificateException
                || error instanceof CertPathValidatorException;
    }

    private static List<Throwable> cadena(Throwable cause) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable actual = cause; actual != null && vistos.add(actual); actual = actual.getCause()) result.add(actual);
        return result;
    }
}
