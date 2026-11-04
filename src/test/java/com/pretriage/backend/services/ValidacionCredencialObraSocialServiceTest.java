package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.exceptions.CredencialInvalidaException;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.services.validadoresObrasociales.ValidadorCredencialObraSocial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidacionCredencialObraSocialServiceTest {

    @Mock
    private ValidadorCredencialObraSocial validador;

    private final ValidacionCredencialObraSocialService service =
            new ValidacionCredencialObraSocialService();

    @Test
    void validaCredencialCuandoElValidadorAcepta() {
        CredencialRequest request = new CredencialRequest();
        Paciente paciente = new Paciente();

        when(validador.validar(request, paciente)).thenReturn(true);

        assertDoesNotThrow(() -> service.validarCredencialObraSocial(request, paciente, validador));
    }

    @Test
    void rechazaCredencialCuandoElValidadorNoAcepta() {
        CredencialRequest request = new CredencialRequest();
        Paciente paciente = new Paciente();

        when(validador.validar(request, paciente)).thenReturn(false);

        assertThrows(CredencialInvalidaException.class,
                () -> service.validarCredencialObraSocial(request, paciente, validador));
    }
}