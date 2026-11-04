package com.pretriage.backend.repositories;

import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.UsuarioAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoPacientesTest {

    @Autowired
    private RepoPacientes repoPacientes;

    @Autowired
    private TestEntityManager em;

    @Test
    void debeBuscarPacientePorAuth0Id() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|abc");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);

        em.persist(usuario);
        em.persist(paciente);
        em.flush();

        Optional<Paciente> encontrado =
                repoPacientes.findByUsuarioAuthId("auth0|abc");

        assertTrue(encontrado.isPresent());
    }
}
