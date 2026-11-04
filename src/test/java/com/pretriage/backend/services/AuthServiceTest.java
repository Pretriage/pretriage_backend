package com.pretriage.backend.services;

/*
 * Test de integracion donde mockeo las respuestas de la api de auth0 usando wireMockServer
 * */

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pretriage.backend.controllers.dtos.LoginResponseDTO;
import com.pretriage.backend.exceptions.NoSePudoCrearUsuario;
import com.pretriage.backend.exceptions.RefreshTokenInvalidoException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { // se sobreescriben los valores del "application.properties" solo para ejecutar
                                   // este test
                "auth0.client-id.machine-to-machine=AUTH0_M2M_CLIENT_ID",
                "auth0.client-secret.machine-to-machine=AUTH0_M2M_CLIENT_SECRET",
                "auth0.scope.machine-to-machine=AUTH0_M2M_SCOPE",
                "auth0.base-path=http://localhost:8089",
                "auth0.client-id.app=ATUH0_APP_CLIENT_ID",

                // no se usan en estos tests
                "google.api.key=test-api-key",
                "google.places.base-url=http://localhost:8089/v1/places",
})
public class AuthServiceTest {

        @Autowired
        private AuthService service;

        private static WireMockServer wireMockServer;

        @BeforeAll
        static void beforeAll() {
                wireMockServer = new WireMockServer(8089);
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

        @Test
        void sePuedeLogearUnUsuario() {

                String bodyResponseApi = """
                                {
                                	"access_token": "fakeAccessToken",
                                        "refresh_token": "fakeRefreshToken",
                                	"id_token": "fakeIdToken",
                                	"scope": "openid profile email offline_access",
                                	"expires_in": 86400,
                                	"token_type": "Bearer"
                                }
                                """;

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponseApi)));

                LoginResponseDTO tokenResponse = service.obtenerTokenParaLogearUsuario("someEmail@gmail.com",
                                "somepass123");

                assertEquals("fakeAccessToken", tokenResponse.getToken());
                assertEquals("fakeRefreshToken", tokenResponse.getRefreshToken());
                assertEquals(86400, tokenResponse.getRenovarTokenEn());
        }

        @Test
        void sePuedeRegistrarUnUsuario() {

                String bodyRequestToken = """
                                {
                                "grant_type": "client_credentials",
                                 "audience": "http://localhost:8089/api/v2/",
                                 "client_secret": "AUTH0_M2M_CLIENT_SECRET",
                                 "scope": "AUTH0_M2M_SCOPE",
                                 "client_id": "AUTH0_M2M_CLIENT_ID"
                                }
                                """;

                String bodyRequestTokenLogin = """
                                {
                                  "grant_type": "http://auth0.com/oauth/grant-type/password-realm",
                                  "username": "someEmail@gmail.com",
                                  "password": "somepass123",
                                  "audience": "http://localhost:8080",
                                  "scope": "openid profile email offline_access",
                                  "client_id": "ATUH0_APP_CLIENT_ID",
                                	"realm": "Username-Password-Authentication"
                                }
                                """;

                String bodyResponseApiGetToken = """
                                {
                                "access_token": "accesstoken",
                                "scope": "read:users update:users delete:users create:users",
                                "expires_in": 86400,
                                "token_type": "Bearer"
                                }
                                """;

                String bodyResponseApiLoginToken = """
                                {
                                "access_token": "accesstoken",
                                "id_token": "idtoken",
                                "refresh_token": "fakeRefreshToken",
                                "scope": "read:users update:users delete:users create:users",
                                "expires_in": 86400,
                                "token_type": "Bearer"
                                }
                                """;

                String bodyResponseApiCreateUser = """
                                {
                                "user_id": "auth0IdFake",
                                "created_at": "2026-06-17T22:42:09.866Z",
                                "email": "someEmail@gmail.com",
                                "email_verified": false,
                                "identities": [
                                 {
                                  "connection": "Username-Password-Authentication",
                                   "user_id": "123",
                                   		"provider": "auth0",
                                   		"isSocial": false
                                   	}
                                  ],
                                   "name": "someEmail@gmail.com",
                                   "nickname": "usuario",
                                   "picture": "...png",
                                   "updated_at": "2026-06-17T22:42:09.867Z"
                                   }
                                """;

                String bodyRequestCreateUser = """
                                {
                                  "email": "someEmail@gmail.com",
                                  "password": "somepass123",
                                  "connection": "Username-Password-Authentication"
                                }
                                """;

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token")).withRequestBody(equalToJson(bodyRequestToken))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponseApiGetToken)));

                wireMockServer.stubFor(post(urlEqualTo("/api/v2/users"))
                                .withHeader("Authorization", equalTo("Bearer accesstoken"))
                                .withHeader("Content-Type", containing("application/json"))
                                .withRequestBody(equalToJson(bodyRequestCreateUser))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponseApiCreateUser)));

                wireMockServer.stubFor(
                                post(urlEqualTo("/oauth/token")).withRequestBody(equalToJson(bodyRequestTokenLogin))
                                                .willReturn(aResponse()
                                                                .withStatus(200)
                                                                .withHeader("Content-Type", "application/json")
                                                                .withBody(bodyResponseApiLoginToken)));

                String responseService = service.registrarUsuarioYObtenerAuth0Id("someEmail@gmail.com", "somepass123");

                assertEquals("auth0IdFake", responseService);
        }

        @Test
        void informaCuandoAuth0RechazaUnaContrasenaDebil() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {"access_token":"access-token","token_type":"Bearer"}
                                                                """)));

                wireMockServer.stubFor(post(urlEqualTo("/api/v2/users"))
                                .withRequestBody(matchingJsonPath("$.email", equalTo("weak@example.com")))
                                .willReturn(aResponse()
                                                .withStatus(400)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "error": "Bad Request",
                                                                  "message": "PasswordStrengthError: Password is too weak"
                                                                }
                                                                """)));

                NoSePudoCrearUsuario error = assertThrows(
                                NoSePudoCrearUsuario.class,
                                () -> service.registrarUsuarioYObtenerAuth0Id("weak@example.com", "12345678"));

                assertEquals(
                                "La contraseña es demasiado débil. Usá mayúsculas, minúsculas, números y símbolos.",
                                error.getMessage());
        }

        @Test
        void reutilizaUsuarioAuth0HuerfanoSiLasCredencialesSonValidas() {
                String tokenRequest = """
                                {
                                  "grant_type": "client_credentials",
                                  "audience": "http://localhost:8089/api/v2/",
                                  "client_secret": "AUTH0_M2M_CLIENT_SECRET",
                                  "scope": "AUTH0_M2M_SCOPE",
                                  "client_id": "AUTH0_M2M_CLIENT_ID"
                                }
                                """;
                String loginRequest = """
                                {
                                  "grant_type": "http://auth0.com/oauth/grant-type/password-realm",
                                  "username": "orphan@example.com",
                                  "password": "Strong!2026Password",
                                  "audience": "http://localhost:8080",
                                  "scope": "openid profile email offline_access",
                                  "client_id": "ATUH0_APP_CLIENT_ID",
                                  "realm": "Username-Password-Authentication"
                                }
                                """;

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(equalToJson(tokenRequest))
                                .willReturn(okJson("{\"access_token\":\"access-token\"}")));
                wireMockServer.stubFor(post(urlEqualTo("/api/v2/users"))
                                .withHeader("Authorization", equalTo("Bearer access-token"))
                                .willReturn(aResponse()
                                                .withStatus(409)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"error\":\"user_exists\",\"message\":\"The user already exists.\"}")));
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(equalToJson(loginRequest))
                                .willReturn(okJson(
                                                "{\"id_token\":\"nodeberiatomaresto\", \"access_token\":\"eyJhbGciOiJub25lIn0.eyJzdWIiOiJhdXRoMHxleGlzdGluZyJ9.\", \"expires_in\": 86400}")));

                String auth0Id = service.registrarUsuarioYObtenerAuth0Id(
                                "orphan@example.com",
                                "Strong!2026Password");

                assertEquals("auth0|existing", auth0Id);
        }

        @Test
        void sePuedeRenovarLaSesionDelUsuario() {

                String bodyResponseApi = """
                                {
                                	"access_token": "fakeAccessToken",
                                        "refresh_token": "fakeRefreshToken2",
                                	"id_token": "fakeIdToken",
                                	"scope": "openid profile email offline_access",
                                	"expires_in": 86400,
                                	"token_type": "Bearer"
                                }
                                """;

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponseApi)));

                LoginResponseDTO tokenResponse = service.renovarTokenUsuario("fakeRefreshToken1");

                assertEquals("fakeAccessToken", tokenResponse.getToken());
                assertEquals("fakeRefreshToken2", tokenResponse.getRefreshToken());// cada refresh token invalida el
                                                                                   // anterior por seguridad
                assertEquals(86400, tokenResponse.getRenovarTokenEn());// segundos
        }

        @Test
        void renovarFallaCuandoRefreshTokenEsInvalido() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .willReturn(aResponse()
                                                .withStatus(400)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "error": "invalid_grant",
                                                                  "error_description": "Invalid refresh token"
                                                                }
                                                                """)));

                RefreshTokenInvalidoException error = assertThrows(
                                RefreshTokenInvalidoException.class,
                                () -> service.renovarTokenUsuario("refreshInvalido"));

                assertEquals("El refresh token es inválido o expiró. Iniciá sesión nuevamente.", error.getMessage());
        }

        @Test
        void renovarFallaCuandoRefreshTokenExpiro() {
                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .willReturn(aResponse()
                                                .withStatus(401)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "error": "invalid_grant",
                                                                  "error_description": "Unknown or invalid refresh token."
                                                                }
                                                                """)));

                assertThrows(
                                RefreshTokenInvalidoException.class,
                                () -> service.renovarTokenUsuario("refreshExpirado"));
        }

        @Test
        void renovarVerificaRotacionCadaRefreshInvalidaAnterior() {
                // Auth0 con rotación: cada refresh genera uno nuevo; simulamos dos llamadas
                String bodyResponse1 = """
                                {
                                  "access_token": "token1",
                                  "refresh_token": "refresh2",
                                  "id_token": "id1",
                                  "expires_in": 86400,
                                  "token_type": "Bearer"
                                }
                                """;
                String bodyResponse2 = """
                                {
                                  "error": "invalid_grant",
                                  "error_description": "Invalid refresh token"
                                }
                                """;

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(containing("refresh2"))
                                .willReturn(aResponse()
                                                .withStatus(400)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponse2)));

                wireMockServer.stubFor(post(urlEqualTo("/oauth/token"))
                                .withRequestBody(containing("refresh1"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(bodyResponse1)));

                LoginResponseDTO primera = service.renovarTokenUsuario("refresh1");
                assertEquals("refresh2", primera.getRefreshToken());

                // reutilizar refresh1 (ya rotado) debe fallar como invalid_grant
                assertThrows(RefreshTokenInvalidoException.class,
                                () -> service.renovarTokenUsuario("refresh2"));
        }

}
