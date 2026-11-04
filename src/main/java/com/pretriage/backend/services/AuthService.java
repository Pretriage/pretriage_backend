package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.LoginResponseDTO;
import com.pretriage.backend.controllers.dtos.auth0.AuthIdTokenResponse;
import com.pretriage.backend.controllers.dtos.auth0.AuthTokenResponse;
import com.pretriage.backend.controllers.dtos.auth0.AuthUserDetailsResponse;
import com.pretriage.backend.exceptions.NoSePudoCambiarContraseniaException;
import com.pretriage.backend.exceptions.NoSePudoCrearUsuario;
import com.pretriage.backend.exceptions.RefreshTokenInvalidoException;
import com.nimbusds.jwt.JWTParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.text.ParseException;

@Service
@RequiredArgsConstructor
public class AuthService {

    @Value("${auth0.base-path}")
    private String AUTH0_BASE_PATH;

    @Value("${auth0.client-id.machine-to-machine}")
    private String AUTH0_M2M_CLIENT_ID;

    @Value("${auth0.client-secret.machine-to-machine}")
    private String AUTH0_M2M_CLIENT_SECRET;

    @Value("${auth0.scope.machine-to-machine}")
    private String AUTH0_M2M_SCOPE;

    @Value("${auth0.client-id.app}")
    private String AUTH0_APP_CLIENT_ID;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // contantes para evitar repetición de strings

    private static final String USERNAME_FIELD = "username";
    private static final String EMAIL_FIELD = "email";
    private static final String PASSWORD_FIELD = "password";
    private static final String CONNECTION_FIELD = "connection";
    private static final String CLIENT_ID_FIELD = "client_id";
    private static final String AUDIENCE_FIELD = "audience";
    private static final String SCOPE_FIELD = "scope";
    private static final String GRANT_TYPE_FIELD = "grant_type";

    private static final String REALM_NAME = "Username-Password-Authentication";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String registrarUsuarioYObtenerAuth0Id(String email, String password) {
        try {
            String tokenParaCrearUsuario = this.obtenerTokenParaCrearUsuario();

            Map<String, String> bodyRequest = Map.of(
                    EMAIL_FIELD, email,
                    PASSWORD_FIELD, password,
                    CONNECTION_FIELD, REALM_NAME);

            String responseUserDetails = this.llamarApiToken(bodyRequest,
                    AUTH0_BASE_PATH + "/api/v2/users",
                    tokenParaCrearUsuario);

            AuthUserDetailsResponse userDetailsNuevo = objectMapper.readValue(responseUserDetails,
                    AuthUserDetailsResponse.class);

            return userDetailsNuevo.getUserId();
        } catch (Auth0UsuarioExistenteException exception) {
            return obtenerAuth0IdPorCredenciales(email, password);
        }
    }

    private String obtenerAuth0IdPorCredenciales(String email, String password) {
        LoginResponseDTO response = obtenerTokenParaLogearUsuario(email, password);
        try {
            String subject = JWTParser.parse(response.getToken()).getJWTClaimsSet().getSubject();
            if (subject == null || subject.isBlank()) {
                throw new NoSePudoCrearUsuario();
            }
            return subject;
        } catch (ParseException exception) {
            log.error("Auth0 devolvió un id_token inválido", exception);
            throw new NoSePudoCrearUsuario();
        }
    }

    public LoginResponseDTO obtenerTokenParaLogearUsuario(String email, String password) {
        Map<String, String> bodyTokenRequest = Map.of(
                GRANT_TYPE_FIELD, "http://auth0.com/oauth/grant-type/password-realm",
                USERNAME_FIELD, email,
                PASSWORD_FIELD, password,
                AUDIENCE_FIELD, "http://localhost:8080",
                SCOPE_FIELD, "openid profile email offline_access",
                CLIENT_ID_FIELD, AUTH0_APP_CLIENT_ID,
                "realm", REALM_NAME);
        String responseJson = this.llamarApiToken(bodyTokenRequest,
                AUTH0_BASE_PATH + "/oauth/token",
                null);

        AuthIdTokenResponse response = objectMapper.readValue(responseJson, AuthIdTokenResponse.class);

        return this.mapToLoginResponseDTO(response);
    }

    public LoginResponseDTO renovarTokenUsuario(String refreshToken) {
        Map<String, String> bodyRefreshRequest = Map.of(
                GRANT_TYPE_FIELD, "refresh_token",
                CLIENT_ID_FIELD, AUTH0_APP_CLIENT_ID,
                "refresh_token", refreshToken);

        String responseJson = this.llamarApiToken(
                bodyRefreshRequest,
                AUTH0_BASE_PATH + "/oauth/token",
                null);

        AuthIdTokenResponse response = objectMapper.readValue(responseJson, AuthIdTokenResponse.class);

        return this.mapToLoginResponseDTO(response);
    }

    /**
     * Guarda la nueva contraseña del usuario en Auth0 (PATCH
     * /api/v2/users/{id} con token M2M). Método compartido entre el flujo de
     * restablecimiento por token y el cambio autenticado, para no duplicar la
     * lógica M2M.
     */
    public void guardarContraseniaEnAuth0(String auth0Id, String nuevaContrasenia) {
        try {
            String tokenM2m = this.obtenerTokenParaCrearUsuario();

            Map<String, String> bodyRequest = Map.of(
                    PASSWORD_FIELD, nuevaContrasenia,
                    CONNECTION_FIELD, REALM_NAME);

            restClient.patch()
                    .uri(AUTH0_BASE_PATH + "/api/v2/users/" + auth0Id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenM2m)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyRequest)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Contraseña actualizada en Auth0 para {}", auth0Id);

        } catch (RestClientResponseException e) {
            log.error("Auth0 PATCH password falló para {} con status {}", auth0Id, e.getStatusCode(), e);
            throw new NoSePudoCambiarContraseniaException(mensajeSeguroAuth0CambioContrasenia(e));
        } catch (NoSePudoCambiarContraseniaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado al cambiar contraseña Auth0 para {}", auth0Id, e);
            throw new NoSePudoCambiarContraseniaException();
        }
    }

    private String obtenerTokenParaCrearUsuario() {
        Map<String, String> bodyRequest = Map.of(
                CLIENT_ID_FIELD, AUTH0_M2M_CLIENT_ID,
                "client_secret", AUTH0_M2M_CLIENT_SECRET,
                AUDIENCE_FIELD, AUTH0_BASE_PATH + "/api/v2/",
                SCOPE_FIELD, AUTH0_M2M_SCOPE,
                GRANT_TYPE_FIELD, "client_credentials");

        String responseJson = this.llamarApiToken(
                bodyRequest,
                AUTH0_BASE_PATH + "/oauth/token",
                null);

        AuthTokenResponse response = objectMapper.readValue(responseJson, AuthTokenResponse.class);

        return response.getAccessToken();
    }

    private String llamarApiToken(Map<String, String> bodyRequest,
            String uriPath,
            String bearerToken) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(uriPath)
                    .contentType(MediaType.APPLICATION_JSON);

            if (bearerToken != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }

            String responseJson = request
                    .body(bodyRequest)
                    .retrieve()
                    .body(String.class);

            if (responseJson == null) {
                log.warn("no se pudo crear el usuario");
                throw new NoSePudoCrearUsuario();
            }

            return responseJson;

        } catch (RestClientResponseException e) {
            log.error("Auth0 API respondió con estado {}", e.getStatusCode(), e);
            if (esRefreshTokenRequest(bodyRequest) && esRefreshTokenInvalido(e)) {
                throw new RefreshTokenInvalidoException();
            }
            if (esUsuarioExistente(e)) {
                throw new Auth0UsuarioExistenteException();
            }
            throw new NoSePudoCrearUsuario(mensajeSeguroAuth0(e));
        } catch (RefreshTokenInvalidoException e) {
            throw e;
        } catch (NoSePudoCrearUsuario e) {
            throw e;
        } catch (Exception e) {
            log.error("Error al crear el usuario con Auth0 API: {}", e.getMessage(), e);
            throw new NoSePudoCrearUsuario();
        }
    }

    private String mensajeSeguroAuth0(RestClientResponseException exception) {
        String response = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);

        if (response.contains("passwordstrengtherror") || response.contains("password is too weak")) {
            return "La contraseña es demasiado débil. Usá mayúsculas, minúsculas, números y símbolos.";
        }
        if (response.contains("wrong email or password") || response.contains("invalid_grant")) {
            return "La cuenta ya existe en Auth0, pero la contraseña ingresada no coincide.";
        }
        if (response.contains("service not enabled within domain")) {
            return "La API de administración de Auth0 no está habilitada para la audiencia configurada.";
        }

        return "No se pudo crear el usuario en Auth0. Revisá la configuración o intentá nuevamente.";
    }

    private String mensajeSeguroAuth0CambioContrasenia(RestClientResponseException exception) {
        String response = "";
        try {
            response = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }

        if (response.contains("passwordstrengtherror") || response.contains("password is too weak")) {
            return "La contraseña es demasiado débil. Usá mayúsculas, minúsculas, números y símbolos.";
        }
        if (response.contains("password is too common") || response.contains("passwordistoocommon")) {
            return "La contraseña es demasiado común. Elegí otra más segura.";
        }
        if (response.contains("user not found") || response.contains("404")) {
            return "Usuario no encontrado en Auth0.";
        }
        return "No se pudo cambiar la contraseña. Revisá la política de contraseñas e intentá nuevamente.";
    }

    private boolean esUsuarioExistente(RestClientResponseException exception) {
        String response = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return exception.getStatusCode().value() == 409
                || response.contains("already exists")
                || response.contains("user_exists");
    }

    private boolean esRefreshTokenRequest(Map<String, String> bodyRequest) {
        return "refresh_token".equals(bodyRequest.get(GRANT_TYPE_FIELD));
    }

    private boolean esRefreshTokenInvalido(RestClientResponseException exception) {
        String response = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return response.contains("invalid_grant")
                || response.contains("invalid_request")
                || response.contains("invalid refresh token")
                || response.contains("unknown or invalid refresh token");
    }

    private LoginResponseDTO mapToLoginResponseDTO(AuthIdTokenResponse auth0Response) {
        LoginResponseDTO dto = new LoginResponseDTO();

        dto.setRenovarTokenEn(Long.valueOf(auth0Response.getExpiresIn()));
        dto.setRefreshToken(auth0Response.getRefreshToken());
        dto.setToken(auth0Response.getAccessToken());

        return dto;
    }

    private static class Auth0UsuarioExistenteException extends RuntimeException {
    }
}
