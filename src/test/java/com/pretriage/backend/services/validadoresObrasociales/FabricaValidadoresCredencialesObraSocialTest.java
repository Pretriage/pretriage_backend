package com.pretriage.backend.services.validadoresObrasociales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


@ExtendWith(MockitoExtension.class)
class FabricaValidadoresCredencialesObraSocialTest {

    @Mock
    private ValidadorCredencialObraSocial validadorOSDE;

    @Mock
    private ValidadorCredencialObraSocial validadorIOMA;

    private FabricaValidadoresCredencialesObraSocial fabrica;

    @BeforeEach
    void setUp() {
        fabrica = new FabricaValidadoresCredencialesObraSocial(
                List.of(validadorOSDE, validadorIOMA));
    }
    /*
     * @Test
     * void devuelveElValidadorQueCoincideConLaObraSocial() {
     * when(validadorOSDE.getObraSocial()).thenReturn("OSDE");
     * when(validadorIOMA.getObraSocial()).thenReturn("IOMA");
     * 
     * assertEquals(validadorOSDE, fabrica.obtenerValidador("OSDE"));
     * assertEquals(validadorIOMA, fabrica.obtenerValidador("IOMA"));
     * }
     */

    @Test
    void coincideIgnorandoMayusculasMinusculas() {
        // when(validadorOSDE.getObraSocial()).thenReturn("OSDE");

        assertEquals(validadorOSDE, fabrica.obtenerValidador("osde"));
    }

    /*
     * @Test
     * void lanzaExcepcionSiNoHayValidadorParaLaObraSocial() {
     * when(validadorOSDE.getObraSocial()).thenReturn("OSDE");
     * when(validadorIOMA.getObraSocial()).thenReturn("IOMA");
     * 
     * assertThrows(ObraSocialSinValidadorException.class,
     * () -> fabrica.obtenerValidador("OBRA SOCIAL SIN VALIDADOR"));
     * }
     */
}