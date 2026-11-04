package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.services.validadoresObrasociales.MockValidadorCredencialObraSocial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockValidadorCredencialObraSocialTest {

    @Test
    void mockValidadorAceptaTodaCredencial() {
        MockValidadorCredencialObraSocial validador = new MockValidadorCredencialObraSocial();

        assertTrue(validador.validar(new CredencialRequest(), null));
    }

    @Test
    void mockValidadorDeclaraLaObraSocialQueCubre() {
        MockValidadorCredencialObraSocial validador = new MockValidadorCredencialObraSocial();

        assertEquals("OSDE", validador.getObraSocial());
    }
}