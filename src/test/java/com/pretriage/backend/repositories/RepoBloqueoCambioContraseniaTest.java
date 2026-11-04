package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.BloqueoCambioContrasenia;
import com.pretriage.backend.model.personas.UsuarioAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoBloqueoCambioContraseniaTest {

    @Autowired
    private RepoBloqueoCambioContrasenia repoBloqueoCambioContrasenia;

    @Autowired
    private TestEntityManager entityManager;

    private UsuarioAuth persistirUsuario(String auth0Id) {
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId(auth0Id);
        entityManager.persist(usuario);
        return usuario;
    }

    @Test
    void persisteUnBloqueoPorUsuario() {
        UsuarioAuth usuario = persistirUsuario("auth0|bloqueo-1");

        BloqueoCambioContrasenia bloqueo = new BloqueoCambioContrasenia(usuario, LocalDateTime.now());
        repoBloqueoCambioContrasenia.save(bloqueo);
        entityManager.flush();
        entityManager.clear();

        Optional<BloqueoCambioContrasenia> guardado = repoBloqueoCambioContrasenia.findByUsuarioId(usuario.getId());

        assertTrue(guardado.isPresent());
        assertEquals(0, guardado.get().getIntentosFallidos());
        assertNull(guardado.get().getBloqueadoHasta());
    }

    @Test
    void incrementaYReseteaElContadorDeIntentos() {
        UsuarioAuth usuario = persistirUsuario("auth0|bloqueo-2");

        BloqueoCambioContrasenia bloqueo = new BloqueoCambioContrasenia(usuario, LocalDateTime.now());
        bloqueo.setIntentosFallidos(bloqueo.getIntentosFallidos() + 1);
        bloqueo.setIntentosFallidos(bloqueo.getIntentosFallidos() + 1);
        repoBloqueoCambioContrasenia.save(bloqueo);
        entityManager.flush();
        entityManager.clear();

        BloqueoCambioContrasenia guardado = repoBloqueoCambioContrasenia.findByUsuarioId(usuario.getId()).orElseThrow();
        assertEquals(2, guardado.getIntentosFallidos());

        guardado.setIntentosFallidos(0);
        guardado.setBloqueadoHasta(null);
        repoBloqueoCambioContrasenia.save(guardado);
        entityManager.flush();
        entityManager.clear();

        BloqueoCambioContrasenia reseteado = repoBloqueoCambioContrasenia.findByUsuarioId(usuario.getId()).orElseThrow();
        assertEquals(0, reseteado.getIntentosFallidos());
        assertFalse(reseteado.estaBloqueado(LocalDateTime.now()));
    }

    @Test
    void noDevuelveBloqueoDeOtroUsuario() {
        persistirUsuario("auth0|bloqueo-3");

        Optional<BloqueoCambioContrasenia> inexistente = repoBloqueoCambioContrasenia.findByUsuarioId("auth0|otro");

        assertFalse(inexistente.isPresent());
    }
}
