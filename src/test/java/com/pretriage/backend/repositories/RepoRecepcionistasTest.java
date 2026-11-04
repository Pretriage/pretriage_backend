package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.Recepcionista;
import com.pretriage.backend.model.personas.UsuarioAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;


import static org.junit.jupiter.api.Assertions.assertTrue;
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoRecepcionistasTest {

    @Autowired
    RepoRecepcionistas repoRecepcionistas;

    @Autowired
    TestEntityManager em;

    @Test
    void debeEncontrarRecepcionistaPorAuth0Id() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|recepcionista");

        Recepcionista recepcionista = new Recepcionista();
        recepcionista.setUsuarioAuth(usuario);

        em.persist(usuario);
        em.persist(recepcionista);
        em.flush();

        assertTrue(
                repoRecepcionistas
                        .existsByUsuarioAuthId("auth0|recepcionista")
        );
    }
}
