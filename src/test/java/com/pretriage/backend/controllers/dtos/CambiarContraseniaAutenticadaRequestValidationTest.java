package com.pretriage.backend.controllers.dtos;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de las reglas de fortaleza de la contraseña nueva expresadas con
 * anotaciones estándar de Bean Validation (@NotBlank + @Pattern por regla)
 * sobre {@link CambiarContraseniaAutenticadaRequest}: cada regla válida e
 * inválida, los límites 8 y 32 y caracteres Unicode (ñ, acentuados).
 */
class CambiarContraseniaAutenticadaRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void beforeAll() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void afterAll() {
        factory.close();
    }

    private Set<ConstraintViolation<CambiarContraseniaAutenticadaRequest>> violations;

    @BeforeEach
    void setUp() {
        violations = null;
    }

    private Set<ConstraintViolation<CambiarContraseniaAutenticadaRequest>> validar(String actual, String nueva) {
        CambiarContraseniaAutenticadaRequest dto = new CambiarContraseniaAutenticadaRequest();
        dto.setContraseniaActual(actual);
        dto.setContraseniaNueva(nueva);
        return validator.validate(dto);
    }

    private boolean esValida(String nueva) {
        return validar("Actual123!", nueva).isEmpty();
    }

    private List<String> mensajesDe(String campo) {
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toList());
    }

    // --- reglas válidas ---

    @Test
    void aceptaUnaContrasenaValidaConNumero() {
        assertTrue(esValida("Abcdef12"));
    }

    @Test
    void aceptaUnaContrasenaValidaConEspecial() {
        assertTrue(esValida("Abcdefg!"));
    }

    @Test
    void aceptaContrasenaEnElLimiteInferiorDe8Caracteres() {
        assertTrue(esValida("Ab1!abcd"));
    }

    @Test
    void aceptaContrasenaEnElLimiteSuperiorDe32Caracteres() {
        assertTrue(esValida("Ab1!abcdefghijkmnopqrstuvwxyz12")); // 32 code points
    }

    @Test
    void aceptaCaracteresUnicodeEnLaLongitudPorPuntoDeCodigo() {
        // ñ y acentos cuentan como un carácter cada uno: 8 code points
        assertTrue(esValida("ÑñÁé1!aB"));
    }

    @Test
    void trataLosAcentosComoMinusculasYLasTildesEnMayusculasComoMayusculas() {
        // sin dígito ni especial -> inválida, pero Ñ cuenta como mayúscula y é como minúscula
        violations = validar("Actual123!", "ÑÑÉÉññéé");
        assertFalseHayViolacionDeFortaleza();
        assertTrue(mensajesDe("contraseniaNueva")
                .contains("La contraseña debe contener al menos un número o carácter especial"));
    }

    private void assertFalseHayViolacionDeFortaleza() {
        List<String> mensajes = mensajesDe("contraseniaNueva");
        assertTrue(mensajes.stream().noneMatch(m -> m.contains("letra mayúscula")),
                "no debería fallar la regla de mayúscula: " + mensajes);
        assertTrue(mensajes.stream().noneMatch(m -> m.contains("letra minúscula")),
                "no debería fallar la regla de minúscula: " + mensajes);
    }

    // --- longitud ---

    @Test
    void rechazaContrasenaMasCortaQue8Caracteres() {
        violations = validar("Actual123!", "Ab1!abc");
        assertEquals(1, mensajesDe("contraseniaNueva")
                .stream().filter(m -> m.contains("entre 8 y 32")).count());
    }

    @Test
    void rechazaContrasenaMasLargaQue32Caracteres() {
        violations = validar("Actual123!", "Ab1!abcdefghijklmnopqrstuvwxyzabc"); // 33
        assertEquals(1, mensajesDe("contraseniaNueva")
                .stream().filter(m -> m.contains("entre 8 y 32")).count());
    }

    @Test
    void rechazaContrasenaDeMasDe32PuntosDeCodigoUnicode() {
        violations = validar("Actual123!", "ÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑÑ1a"); // 34 code points
        assertEquals(1, mensajesDe("contraseniaNueva")
                .stream().filter(m -> m.contains("entre 8 y 32")).count());
    }

    // --- mayúsculas / minúsculas / número o especial ---

    @Test
    void rechazaContrasenaSinMayuscula() {
        violations = validar("Actual123!", "abcdef12");
        assertTrue(mensajesDe("contraseniaNueva")
                .contains("La contraseña debe contener al menos una letra mayúscula"));
    }

    @Test
    void rechazaContrasenaSinMinuscula() {
        violations = validar("Actual123!", "ABCDEF12");
        assertTrue(mensajesDe("contraseniaNueva")
                .contains("La contraseña debe contener al menos una letra minúscula"));
    }

    @Test
    void rechazaContrasenaSinNumeroNiEspecial() {
        violations = validar("Actual123!", "Abcdefgh");
        assertTrue(mensajesDe("contraseniaNueva")
                .contains("La contraseña debe contener al menos un número o carácter especial"));
    }

    @Test
    void elEspacioNoCuentaComoCaracterEspecial() {
        violations = validar("Actual123!", "Abcdefg ");
        assertTrue(mensajesDe("contraseniaNueva")
                .contains("La contraseña debe contener al menos un número o carácter especial"));
    }

    @Test
    void reportaCadaReglaIncumplida() {
        // 3 caracteres, sin mayúscula y sin número ni especial -> 3 reglas
        violations = validar("Actual123!", "abc");
        List<String> mensajes = mensajesDe("contraseniaNueva");
        assertEquals(3, mensajes.size(), "mensajes: " + mensajes);
        assertTrue(mensajes.contains("La contraseña debe tener entre 8 y 32 caracteres"));
        assertTrue(mensajes.contains("La contraseña debe contener al menos una letra mayúscula"));
        assertTrue(mensajes.contains("La contraseña debe contener al menos un número o carácter especial"));
    }

    // --- campos obligatorios: los resuelve @NotBlank ---

    @Test
    void rechazaContrasenaNuevaNulaOVaciaConNotBlank() {
        assertTrue(!validar("Actual123!", null).isEmpty());
        assertTrue(!validar("Actual123!", "").isEmpty());
        assertTrue(!validar("Actual123!", "        ").isEmpty());
    }

    @Test
    void rechazaContraseniaActualEnBlancoConNotBlank() {
        violations = validar("  ", "Nueva123!");
        assertTrue(mensajesDe("contraseniaActual")
                .contains("La contraseña actual es obligatoria"));
    }
}
