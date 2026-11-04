package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Credencial;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.UsuarioAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoCredencialesTest {

    @Autowired
    private RepoCredenciales repoCredenciales;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void debeEncontrarCredencialVigente() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|1");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);

        entityManager.persist(usuario);
        entityManager.persist(paciente);

        Credencial credencial = new Credencial();
        credencial.setPaciente(paciente);
        credencial.setNumeroAfiliado("123456");
        credencial.setPlan("210");
        credencial.setFechaVencimiento(LocalDate.now().plusDays(30));

        entityManager.persist(credencial);
        entityManager.flush();

        boolean existe = repoCredenciales
                .existsByPacienteIdAndFechaVencimientoGreaterThanEqual(
                        paciente.getId(),
                        LocalDate.now());

        assertTrue(existe);
    }

    @Test
    void noDebeEncontrarCredencialVencida() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|1");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);

        entityManager.persist(usuario);
        entityManager.persist(paciente);

        Credencial credencial = new Credencial();
        credencial.setPaciente(paciente);
        credencial.setFechaVencimiento(LocalDate.now().minusDays(1));

        entityManager.persist(credencial);
        entityManager.flush();

        boolean existe = repoCredenciales
                .existsByPacienteIdAndFechaVencimientoGreaterThanEqual(
                        paciente.getId(),
                        LocalDate.now());

        assertFalse(existe);
    }
}
