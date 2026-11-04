package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ProveedorIaException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;

/** Coordinates a small, durable retry budget for a closed voice interview. */
@Service
public class ClasificacionVozRecoveryCoordinator {
    public static final int MAX_INTENTOS = 2;
    private static final Duration MAX_PRESUPUESTO = Duration.ofSeconds(91);
    private static final Duration MAX_POR_OPERACION = Duration.ofSeconds(45);
    private static final Duration BACKOFF = Duration.ofMillis(500);

    @FunctionalInterface
    public interface Attempt<T> {
        T run(int attemptNumber, Duration remainingBudget);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Sleeper sleeper;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public static final class EnCursoException extends RuntimeException { }

    public ClasificacionVozRecoveryCoordinator() {
        this(duration -> Thread.sleep(duration.toMillis()));
    }

    ClasificacionVozRecoveryCoordinator(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** Retry only an explicitly classified transient transport/provider failure. */
    public <T> T ejecutar(String key, Attempt<T> attempt, IntSupplier reservarIntento) {
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                return ejecutarSerial(attempt, reservarIntento);
            } finally {
                locks.remove(key, lock);
            }
        }
    }

    private <T> T ejecutarSerial(Attempt<T> attempt, IntSupplier reservarIntento) {
        long inicio = System.nanoTime();
        for (int ciclo = 0; ciclo < MAX_INTENTOS; ciclo++) {
            Duration restante = MAX_PRESUPUESTO.minusNanos(Math.max(0, System.nanoTime() - inicio));
            if (restante.isNegative() || restante.isZero()) {
                throw new IllegalStateException("Se agotó el presupuesto de recuperación de clasificación");
            }
            int numero = reservarIntento.getAsInt();
            if (numero == -1) throw new EnCursoException();
            if (numero < 1 || numero > MAX_INTENTOS) {
                throw new IllegalStateException("No hay intentos automáticos de clasificación disponibles");
            }
            try {
                return attempt.run(numero, restante.compareTo(MAX_POR_OPERACION) < 0 ? restante : MAX_POR_OPERACION);
            } catch (ProveedorIaException error) {
                if (!error.esTransitorio() || numero >= MAX_INTENTOS) throw error;
                if (BACKOFF.compareTo(MAX_PRESUPUESTO.minusNanos(Math.max(0, System.nanoTime() - inicio))) >= 0) {
                    throw error;
                }
                try {
                    sleeper.sleep(BACKOFF);
                } catch (InterruptedException interrupcion) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
        throw new IllegalStateException("No hay intentos automáticos de clasificación disponibles");
    }
}
