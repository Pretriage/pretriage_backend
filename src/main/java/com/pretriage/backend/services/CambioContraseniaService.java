package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.SolicitarTokenCambioContraseniaResponse;
import com.pretriage.backend.exceptions.LimiteSolicitudesCambioContraseniaException;
import com.pretriage.backend.exceptions.TokenCambioContraseniaInvalidoException;
import com.pretriage.backend.model.personas.CambioContraseniaToken;
import com.pretriage.backend.model.personas.EstadoCambioContrasenia;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoCambioContraseniaToken;
import com.pretriage.backend.repositories.RepoUsuariosAuth;
import com.pretriage.backend.services.ports.PasswordResetEmailPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CambioContraseniaService {

    private static final String MENSAJE_GENERICO = "Si el correo existe, le enviamos un token al correo indicado, por favor, revise su mail e ingrese el token";

    private final RepoUsuariosAuth repoUsuariosAuth;
    private final RepoCambioContraseniaToken repoCambioContraseniaToken;
    private final TokenService tokenService;
    private final PasswordResetEmailPort passwordResetEmailPort;
    private final AuthService authService;

    @Value("${pretriage.cambio-contrasenia.expiracion-minutos:15}")
    private long expiracionMinutos;

    @Value("${pretriage.cambio-contrasenia.max-solicitudes-por-hora:3}")
    private int maxSolicitudesPorHora;

    @Value("${pretriage.cambio-contrasenia.ventana-horas:1}")
    private long ventanaHoras;

    // --- API pública según diagrama ---

    @Transactional
    public SolicitarTokenCambioContraseniaResponse obtenerTokenCambioContraseña(String email) {
        SolicitarTokenCambioContraseniaResponse dtoResponse = new SolicitarTokenCambioContraseniaResponse();
        dtoResponse.setTiempoExpiracion(LocalTime.of(0, (int) expiracionMinutos));
        // Privacidad: siempre devolver mensaje genérico para no enumerar usuarios
        dtoResponse.setMensaje(MENSAJE_GENERICO);

        String emailNormalizado = normalizarEmail(email);
        Optional<UsuarioAuth> usuarioOpt = repoUsuariosAuth.findByCorreoElectronicoIgnoreCase(emailNormalizado);

        if (usuarioOpt.isEmpty()) {
            log.info("Solicitud de token para email no existente: {}", emailNormalizado);
            return dtoResponse;
        }

        UsuarioAuth usuario = usuarioOpt.get();

        // rate limit
        if (superaLimiteCambioContrasenia(usuario.getId())) {
            throw new LimiteSolicitudesCambioContraseniaException();
        }

        // invalidar tokens pendientes previos
        invalidarTokensPendientesPrevios(usuario.getId());

        String token = tokenService.generarToken();
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime expiracion = ahora.plusMinutes(expiracionMinutos);

        CambioContraseniaToken nuevo = new CambioContraseniaToken(usuario, token, ahora, expiracion);
        repoCambioContraseniaToken.save(nuevo);

        // envío de email (no bloquea la transacción con excepción)
        try {
            passwordResetEmailPort.enviarToken(emailNormalizado, token, expiracion);
        } catch (Exception e) {
            log.error("Error al enviar email de restablecimiento a {}", emailNormalizado, e);
        }

        return dtoResponse;
    }

    @Transactional(readOnly = true)
    public void validarTokenCambioContrasenia(String token) {
        CambioContraseniaToken cambio = obtenerCambioContraseniaToken(token);
        validarEstadoYExpiracion(cambio);
    }

    @Transactional
    public void cambiarContraseña(String nuevaPass, String tokenCambioContrasenia) {
        CambioContraseniaToken cambio = obtenerCambioContraseniaToken(tokenCambioContrasenia);
        validarEstadoYExpiracion(cambio);

        UsuarioAuth usuario = cambio.getUsuario();
        String auth0Id = usuario.getId();

        llamarApiCambioContrasenia(auth0Id, nuevaPass);

        // marcar como cambiado
        cambio.setEstado(EstadoCambioContrasenia.CAMBIADO);
        repoCambioContraseniaToken.save(cambio);

        // invalidar otros pendientes del usuario (por si quedaron)
        invalidarOtrosPendientes(usuario.getId(), cambio.getId());
    }

    // --- helpers privados ---

    private boolean superaLimiteCambioContrasenia(String usuarioId) {
        LocalDateTime desde = LocalDateTime.now().minusHours(ventanaHoras);
        long totalRecientes = repoCambioContraseniaToken
                .countByUsuarioIdAndFechaHoraCreacionAfter(usuarioId, desde);
        return totalRecientes >= maxSolicitudesPorHora;
    }

    private void validarEstadoYExpiracion(CambioContraseniaToken cambio) {
        if (cambio.getEstado() != EstadoCambioContrasenia.PENDIENTE) {
            throw new TokenCambioContraseniaInvalidoException();
        }
        if (cambio.expiro()) {
            cambio.setEstado(EstadoCambioContrasenia.EXPIRO);
            repoCambioContraseniaToken.save(cambio);
            throw new TokenCambioContraseniaInvalidoException();
        }
    }

    private CambioContraseniaToken obtenerCambioContraseniaToken(String token) {
        return repoCambioContraseniaToken.findByToken(token)
                .orElseThrow(TokenCambioContraseniaInvalidoException::new);
    }

    private void invalidarTokensPendientesPrevios(String usuarioId) {
        List<CambioContraseniaToken> pendientes = repoCambioContraseniaToken
                .findByUsuarioIdAndEstado(usuarioId, EstadoCambioContrasenia.PENDIENTE);
        for (CambioContraseniaToken t : pendientes) {
            t.setEstado(EstadoCambioContrasenia.INVALIDADO);
        }
        if (!pendientes.isEmpty()) {
            repoCambioContraseniaToken.saveAll(pendientes);
        }
    }

    private void invalidarOtrosPendientes(String usuarioId, Long exceptoId) {
        List<CambioContraseniaToken> pendientes = repoCambioContraseniaToken
                .findByUsuarioIdAndEstado(usuarioId, EstadoCambioContrasenia.PENDIENTE);
        for (CambioContraseniaToken t : pendientes) {
            if (!t.getId().equals(exceptoId)) {
                t.setEstado(EstadoCambioContrasenia.INVALIDADO);
            }
        }
        if (!pendientes.isEmpty()) {
            repoCambioContraseniaToken.saveAll(pendientes);
        }
    }

    private void llamarApiCambioContrasenia(String auth0Id, String nuevaPass) {
        // El guardado M2M (PATCH /api/v2/users/{id}) vive en AuthService para
        // no duplicar la lógica entre este flujo y el cambio autenticado.
        authService.guardarContraseniaEnAuth0(auth0Id, nuevaPass);
    }

    private static String normalizarEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
