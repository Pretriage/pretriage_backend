package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ProveedorIaException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClasificacionVozRecoveryCoordinatorTest {
    private final ClasificacionVozRecoveryCoordinator coordinator =
            new ClasificacionVozRecoveryCoordinator(ignored -> { });

    @Test
    void retriesOnlyOneTypedTransientFailure() {
        List<Integer> calls = new ArrayList<>();
        int[] persisted = {0};

        String result = coordinator.ejecutar("chat-1", (attempt, remaining) -> {
            calls.add(attempt);
            if (attempt == 1) throw new ProveedorIaException(new IllegalStateException("timeout"), true);
            return "complete";
        }, () -> ++persisted[0]);

        assertEquals("complete", result);
        assertEquals(List.of(1, 2), calls);
        assertEquals(2, persisted[0]);
    }

    @Test
    void doesNotRetryPermanentProviderFailure() {
        List<Integer> calls = new ArrayList<>();

        int[] reservations = {0};
        assertThrows(ProveedorIaException.class, () -> coordinator.ejecutar("chat-2", (attempt, remaining) -> {
            calls.add(attempt);
            throw new ProveedorIaException(new IllegalArgumentException("invalid response"));
        }, () -> ++reservations[0]));

        assertEquals(List.of(1), calls);
    }

    @Test
    void resumesWithinPersistedBudgetWithoutResettingIt() {
        List<Integer> calls = new ArrayList<>();

        assertThrows(ProveedorIaException.class, () -> coordinator.ejecutar("chat-3", (attempt, remaining) -> {
            calls.add(attempt);
            throw new ProveedorIaException(new IllegalStateException("timeout"), true);
        }, () -> 2));

        assertEquals(List.of(2), calls);
    }
}
