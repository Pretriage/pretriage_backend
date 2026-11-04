package com.pretriage.backend.services;

/*
 * Test de integración del cambio de contraseña autenticado: mockea las
 * respuestas de la API de auth0 usando wireMockServer y usa la base
 * descartable del runner aislado para verificar el conteo de intentos y el bloqueo.
 * */

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.pretriage.backend.controllers.dtos.CambiarContraseniaAutenticadaRequest;
import com.pretriage.backend.controllers.dtos.SolicitarTokenCambioContraseniaResponse;
import com.pretriage.backend.exceptions.CambioContraseniaBloqueadoException;
import com.pretriage.backend.exceptions.ContraseniaInvalidaException;
import com.pretriage.backend.exceptions.NoSePudoCambiarContraseniaException;
import com.pretriage.backend.exceptions.UsuarioLocalNoExisteException;
import com.pretriage.backend.model.personas.BloqueoCambioContrasenia;
import com.pretriage.backend.model.personas.CambioContraseniaToken;
import com.pretriage.backend.model.personas.EstadoCambioContrasenia;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoBloqueoCambioContrasenia;
import com.pretriage.backend.repositories.RepoCambioContraseniaToken;
import com.pretriage.backend.repositories.RepoUsuariosAuth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
                "auth0.client-id.machine-to-machine=AUTH0_M2M_CLIENT_ID",
                "auth0.client-secret.machine-to-machine=AUTH0_M2M_CLIENT_SECRET",
                "auth0.scope.machine-to-machine=AUTH0_M2M_SCOPE",
                "auth0.base-path=http://localhost:8090",
                "auth0.client-id.app=AUTH0_APP_CLIENT_ID",
                "google.api.key=test-api-key",
                "google.places.base-url=http://localhost:8090/v1/places",
                "google.routes.base-url=http://localhost:8089/directions/v2:computeRoutes"
})
public class CambioContraseniaAutenticadoServiceTest {

        private static final AtomicInteger COUNTER = new AtomicInteger();

        @Autowired
        private CambioContraseniaAutenticadoService service;

        @Autowired
        private CambioContraseniaService cambioContraseniaService;

        @Autowired
        private AuthService authService;

        @Autowired
        private RepoUsuariosAuth repoUsuariosAuth;

        @Autowired
        private RepoBloqueoCambioContrasenia repoBloqueoCambioContrasenia;

        @Autowired
        private RepoCambioContraseniaToken repoCambioContraseniaToken;

        private static WireMockServer wireMockServer;

        @BeforeAll
        static void beforeAll() {
                wireMockServer = new WireMockServer(8090);
                wireMockServer.start();
        }

        @AfterAll
        static void afterAll() {
                wireMockServer.stop();
        }

        @BeforeEach
        void resetWireMock() {
                wireMockServer.resetAll();
        }

        // --- helpers ---

        private UsuarioAuth crearUsuario() {
                int n = COUNTER.incrementAndGet();
                UsuarioAuth usuario = new UsuarioAuth();
                usuario.setId("auth0|cambio-auth-" + n);
                usuario.setNombre("Test");
                usuario.setApellido("Test");
                usuario.setCorreoElectronico("cambio-auth-" + n + "@example.com");
                return repoUsuariosAuth.save(usuario);
        }

        private BloqueoCambioContrasenia crearBloqueo(UsuarioAuth usuario, int intentos, LocalDateTime bloqueadoHasta) {
                BloqueoCambioContrasenia bloqueo = new BloqueoCambioContrasenia(usuario, LocalDateTime.now());
                bloqueo.setIntentosFallidos(intentos);
                bloqueo.setBloqueadoHasta(bloqueadoHasta);
                return repoBloqueoCambioContrasenia.save(bloqueo);
        }

        private BloqueoCambioContrasenia bloqueoDe(String usuarioId) {
                return repoBloqueoCambioContrasenia.findByUsuarioId(usuarioId).orElseThrow();
        }

        private CambiarContraseniaAutenticadaRequest request(String actual, String nueva) {
                CambiarContraseniaAutenticadaRequest dto = new CambiarContraseniaAutenticadaRequest();
                dto.setContraseniaActual(actual);
                dto.setContraseniaNueva(nueva);
                return dto;
        }

        private void stubVerificacionContrasenaCorrecta() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(matchingJsonPath("$.grant_type",
                                                equalTo("http://auth0.com/oauth/grant-type/password-realm")))
                                .willReturn(okJson(
                                                "{\"access_token\":\"access-token\",\"id_token\":\"id\",\"expires_in\":86400}")));
        }

        private void stubVerificacionContrasenaIncorrecta() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(matchingJsonPath("$.grant_type",
                                                equalTo("http://auth0.com/oauth/grant-type/password-realm")))
                                .willReturn(aResponse().withStatus(400)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"error\":\"invalid_grant\","
                                                                + "\"error_description\":\"Wrong email or password.\"}")));
        }

        private void stubTokenM2m() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials")))
                                .willReturn(okJson("{\"access_token\":\"m2m-token\"}")));
        }

        private void stubGuardadoOk() {
                wireMockServer.stubFor(patch(urlMatching("/api/v2/users/.*"))
                                .withHeader("Authorization", equalTo("Bearer m2m-token"))
                                .willReturn(aResponse().withStatus(200)));
        }

        private void stubGuardadoRechazado() {
                wireMockServer.stubFor(patch(urlMatching("/api/v2/users/.*"))
                                .willReturn(aResponse().withStatus(400)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"error\":\"Bad Request\","
                                                                + "\"message\":\"PasswordStrengthError: Password is too weak\"}")));
        }

        // --- 3.1: éxito y bloqueo ---

        @Test
        void cambioExitosoReseteaElContadorDeIntentos() {
                UsuarioAuth usuario = crearUsuario();
                crearBloqueo(usuario, 1, null);
                stubVerificacionContrasenaCorrecta();
                stubTokenM2m();
                stubGuardadoOk();

                service.cambiarContrasenia(usuario.getId(), request("Actual123!", "Nueva123!"));

                BloqueoCambioContrasenia bloqueo = bloqueoDe(usuario.getId());
                assertEquals(0, bloqueo.getIntentosFallidos());
                assertNull(bloqueo.getBloqueadoHasta());
        }

        @Test
        void bloqueoActivoRechazaElCambio() {
                UsuarioAuth usuario = crearUsuario();
                crearBloqueo(usuario, 3, LocalDateTime.now().plusHours(23));

                CambioContraseniaBloqueadoException error = assertThrows(CambioContraseniaBloqueadoException.class,
                                () -> service.cambiarContrasenia(usuario.getId(), request("Actual123!", "Nueva123!")));

                assertTrue(error.getMinutosRestantes() > 0);
                assertTrue(error.getMessage().contains(String.valueOf(error.getMinutosRestantes())));
        }

        @Test
        void contraseniaNuevaIgualALaActualIncrementaLosIntentosFallidos() {
                UsuarioAuth usuario = crearUsuario();

                ContraseniaInvalidaException error = assertThrows(ContraseniaInvalidaException.class,
                                () -> service.cambiarContrasenia(usuario.getId(), request("Actual123!", "Actual123!")));

                assertEquals("La contraseña nueva debe ser distinta de la contraseña actual", error.getMessage());
                assertEquals(1, bloqueoDe(usuario.getId()).getIntentosFallidos());
        }

        @Test
        void contraseniaActualIncorrectaIncrementaLosIntentosFallidos() {
                UsuarioAuth usuario = crearUsuario();
                stubVerificacionContrasenaIncorrecta();

                assertThrows(ContraseniaInvalidaException.class,
                                () -> service.cambiarContrasenia(usuario.getId(), request("Mala1234!", "Nueva123!")));

                assertEquals(1, bloqueoDe(usuario.getId()).getIntentosFallidos());
        }

        @Test
        void rechazoDeAuth0AlGuardarIncrementaLosIntentosFallidos() {
                UsuarioAuth usuario = crearUsuario();
                stubVerificacionContrasenaCorrecta();
                stubTokenM2m();
                stubGuardadoRechazado();

                assertThrows(NoSePudoCambiarContraseniaException.class,
                                () -> service.cambiarContrasenia(usuario.getId(), request("Actual123!", "Nueva123!")));

                assertEquals(1, bloqueoDe(usuario.getId()).getIntentosFallidos());
        }

        @Test
        void tresFallosBloqueanUnDiaDesdeElUltimoFallo() {
                UsuarioAuth usuario = crearUsuario();
                stubVerificacionContrasenaIncorrecta();

                for (int i = 0; i < 3; i++) {
                        assertThrows(ContraseniaInvalidaException.class,
                                        () -> service.cambiarContrasenia(usuario.getId(),
                                                        request("Mala1234!", "Nueva123!")));
                }

                BloqueoCambioContrasenia bloqueo = bloqueoDe(usuario.getId());
                assertEquals(3, bloqueo.getIntentosFallidos());
                assertNotNull(bloqueo.getBloqueadoHasta());
                LocalDateTime minimoEsperado = LocalDateTime.now().plusHours(23);
                LocalDateTime maximoEsperado = LocalDateTime.now().plusHours(25);
                assertTrue(bloqueo.getBloqueadoHasta().isAfter(minimoEsperado),
                                "bloqueadoHasta=" + bloqueo.getBloqueadoHasta() + " minimo=" + minimoEsperado);
                assertTrue(bloqueo.getBloqueadoHasta().isBefore(maximoEsperado));

                // cualquier intento posterior responde 423
                assertThrows(CambioContraseniaBloqueadoException.class,
                                () -> service.cambiarContrasenia(usuario.getId(), request("Actual123!", "Nueva123!")));
        }

        @Test
        void bloqueoVencidoReseteaElContadorYUnFalloNoVuelveABloquear() {
                UsuarioAuth usuario = crearUsuario();
                crearBloqueo(usuario, 3, LocalDateTime.now().minusMinutes(1));
                stubVerificacionContrasenaIncorrecta();

                assertThrows(ContraseniaInvalidaException.class,
                                () -> service.cambiarContrasenia(usuario.getId(),
                                                request("Mala1234!", "Nueva123!")));

                BloqueoCambioContrasenia bloqueo = bloqueoDe(usuario.getId());
                assertEquals(1, bloqueo.getIntentosFallidos());
                assertNull(bloqueo.getBloqueadoHasta());
        }

        // --- 3.3: JWT válido sin usuario local ---

        @Test
        void jwtSinUsuarioLocalLanzaExcepcionDe500YRegistraWarn() {
                Logger logger = (Logger) LoggerFactory.getLogger(CambioContraseniaAutenticadoService.class);
                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                appender.start();
                logger.addAppender(appender);
                try {
                        assertThrows(UsuarioLocalNoExisteException.class,
                                        () -> service.cambiarContrasenia("auth0|no-existe",
                                                        request("Actual123!", "Nueva123!")));

                        List<ILoggingEvent> warns = appender.list.stream()
                                        .filter(event -> event.getLevel().toInt() == ch.qos.logback.classic.Level.WARN_INT)
                                        .toList();
                        assertFalse(warns.isEmpty());
                        assertTrue(warns.get(0).getFormattedMessage().contains("no existe en la base local"));
                } finally {
                        logger.detachAppender(appender);
                }
        }

        // --- 3.4: el bloqueo no afecta el resto de la aplicación ---

        @Test
        void usuarioBloqueadoPuedeRestablecerContraseniaPorToken() {
                UsuarioAuth usuario = crearUsuario();
                crearBloqueo(usuario, 3, LocalDateTime.now().plusHours(24));
                stubTokenM2m();
                stubGuardadoOk();

                // flujo por token: solicitud y uso del token con normalidad
                SolicitarTokenCambioContraseniaResponse respuesta = cambioContraseniaService
                                .obtenerTokenCambioContraseña(usuario.getCorreoElectronico());
                assertNotNull(respuesta.getMensaje());

                CambioContraseniaToken token = repoCambioContraseniaToken
                                .findByUsuarioIdAndEstado(usuario.getId(), EstadoCambioContrasenia.PENDIENTE)
                                .get(0);

                cambioContraseniaService.cambiarContraseña("NuevaPorToken123!", token.getToken());

                CambioContraseniaToken tokenActualizado = repoCambioContraseniaToken.findByToken(token.getToken())
                                .orElseThrow();
                assertEquals(EstadoCambioContrasenia.CAMBIADO, tokenActualizado.getEstado());

                // el bloqueo del cambio autenticado sigue intacto y sin efecto colateral
                assertEquals(3, bloqueoDe(usuario.getId()).getIntentosFallidos());
        }

        @Test
        void usuarioBloqueadoPuedeUsarElRestoDeLosEndpointsAutenticados() {
                UsuarioAuth usuario = crearUsuario();
                crearBloqueo(usuario, 3, LocalDateTime.now().plusHours(24));
                stubVerificacionContrasenaCorrecta();

                // login (endpoint autenticado del dominio de credenciales) sigue funcionando
                var login = authService.obtenerTokenParaLogearUsuario(usuario.getCorreoElectronico(), "Actual123!");
                assertEquals("access-token", login.getToken());
        }
}
