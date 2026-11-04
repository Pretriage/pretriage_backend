package com.pretriage.backend.repositories;

import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.UsuarioAuth;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepoSesionesAtencionMedicaLockTest {

    private static final long MAX_WAIT_SECONDS = 5;
    private static final long LOCK_CHECK_MILLIS = 400;

    @Autowired
    private RepoSesionesAtencionMedica repoSesiones;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void lookupDelMedicoEsperaCierreAdminYLeeEstadoFinalizada() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Fixture fixture = crearFixture(transaction);

        CountDownLatch adminTieneLock = new CountDownLatch(1);
        CountDownLatch liberarAdmin = new CountDownLatch(1);
        CountDownLatch medicoInicioLookup = new CountDownLatch(1);
        CountDownLatch medicoTerminoLookup = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> admin = null;

        try {
            admin = executor.submit(() -> transaction.executeWithoutResult(status -> {
                SesionAtencionMedica sesion = repoSesiones.findByIdForUpdate(fixture.sesionId())
                        .orElseThrow();
                sesion.setEstado(EstadoSesionMedica.FINALIZADA);
                sesion.setFechaHoraFin(LocalDateTime.now());
                repoSesiones.save(sesion);
                adminTieneLock.countDown();
                awaitOrFail(liberarAdmin);
            }));

            assertTrue(adminTieneLock.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                    "la transacción admin debe mantener el bloqueo de sesión");

            Future<EstadoSesionMedica> medico = executor.submit(() -> {
                medicoInicioLookup.countDown();
                try {
                    return transaction.execute(status -> repoSesiones
                            .findByIdAndMedicoUsuarioAuthIdForUpdate(fixture.sesionId(), fixture.authId())
                            .map(SesionAtencionMedica::getEstado)
                            .orElse(null));
                } finally {
                    medicoTerminoLookup.countDown();
                }
            });

            assertTrue(medicoInicioLookup.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(medicoTerminoLookup.await(LOCK_CHECK_MILLIS, TimeUnit.MILLISECONDS),
                    "el lookup médico debe esperar el commit del cierre admin");

            liberarAdmin.countDown();
            admin.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(EstadoSesionMedica.FINALIZADA, medico.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                    "al obtener el lock, el médico debe ver el estado finalizado actualizado");
        } finally {
            liberarAdmin.countDown();
            executor.shutdown();
            boolean terminated = executor.awaitTermination(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!terminated) {
                executor.shutdownNow();
                terminated = executor.awaitTermination(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            eliminarFixture(transaction, fixture);
            assertTrue(terminated, "las transacciones de prueba deben terminar");
        }
    }

    private Fixture crearFixture(TransactionTemplate transaction) {
        return transaction.execute(status -> {
            String authId = "lock-test|" + UUID.randomUUID();
            UsuarioAuth usuario = new UsuarioAuth();
            usuario.setId(authId);
            usuario.setNombre("Lock test");
            entityManager.persist(usuario);

            Medico medico = new Medico();
            medico.setUsuarioAuth(usuario);
            medico.setMatricula("LOCK-" + UUID.randomUUID());
            entityManager.persist(medico);

            SesionAtencionMedica sesion = new SesionAtencionMedica();
            sesion.setMedico(medico);
            sesion.setEstado(EstadoSesionMedica.ACTIVA);
            sesion.setFechaHoraInicio(LocalDateTime.now());
            entityManager.persist(sesion);
            entityManager.flush();
            assertNotNull(sesion.getId());
            return new Fixture(sesion.getId(), authId, medico.getId(), authId);
        });
    }

    private void eliminarFixture(TransactionTemplate transaction, Fixture fixture) {
        if (fixture == null) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            SesionAtencionMedica sesion = entityManager.find(SesionAtencionMedica.class, fixture.sesionId());
            if (sesion != null) {
                entityManager.remove(sesion);
            }
            Medico medico = entityManager.find(Medico.class, fixture.medicoId());
            if (medico != null) {
                entityManager.remove(medico);
            }
            UsuarioAuth usuario = entityManager.find(UsuarioAuth.class, fixture.usuarioId());
            if (usuario != null) {
                entityManager.remove(usuario);
            }
        });
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out while holding the session lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while holding the session lock", e);
        }
    }

    private record Fixture(Long sesionId, String authId, Long medicoId, String usuarioId) {
    }
}
