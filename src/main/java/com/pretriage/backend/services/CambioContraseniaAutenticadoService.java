package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CambiarContraseniaAutenticadaRequest;
import com.pretriage.backend.exceptions.CambioContraseniaBloqueadoException;
import com.pretriage.backend.exceptions.ContraseniaInvalidaException;
import com.pretriage.backend.exceptions.NoSePudoCambiarContraseniaException;
import com.pretriage.backend.exceptions.NoSePudoCrearUsuario;
import com.pretriage.backend.exceptions.UsuarioLocalNoExisteException;
import com.pretriage.backend.model.personas.BloqueoCambioContrasenia;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoBloqueoCambioContrasenia;
import com.pretriage.backend.repositories.RepoUsuariosAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Cambio de contraseña autenticado (conocida la actual): verifica la contraseña
 * actual contra Auth0 y persiste los intentos fallidos por usuario con bloqueo
 * temporal configurable. Las reglas de fortaleza de la nueva NO se aplican
 * acá: las resuelve @Valid en el controller y su fallo no cuenta como intento.
 *
 * Nota: no lleva @Transactional en el método porque el intento fallido debe
 * persistirse aunque el método termine lanzando excepción (un @Transactional
 * revertiría el incremento del contador junto con el error).
 */
@Service
@RequiredArgsConstructor 
@Slf4j
public class CambioContraseniaAutenticadoService {

    private final RepoUsuariosAuth repoUsuariosAuth;
    private final RepoBloqueoCambioContrasenia repoBloqueoCambioContrasenia;
    private final AuthService authService;

    @Value("${pretriage.cambio-contrasenia-autenticado.max-intentos:3}")
    private int maxIntentos;

    // 1 día de bloqueo (spec) = 24 horas
    @Value("${pretriage.cambio-contrasenia-autenticado.horas-bloqueo:24}")
    private long horasBloqueo;

    /**
     * @param auth0Id sujeto del JWT (jwt.getSubject()), que es el id de
     *                {@link UsuarioAuth}
     */
    public void cambiarContrasenia(String auth0Id, CambiarContraseniaAutenticadaRequest request) {
        UsuarioAuth usuario = repoUsuariosAuth.findById(auth0Id)
                .orElseThrow(() -> {
                    log.warn("JWT válido sin usuario local: el sujeto '{}' no existe en la base local. "
                            + "Divergencia entre Auth0 y la base de datos.", auth0Id);
                    return new UsuarioLocalNoExisteException(auth0Id);
                });

        BloqueoCambioContrasenia bloqueo = repoBloqueoCambioContrasenia.findByUsuarioId(usuario.getId())
                .orElseGet(() -> new BloqueoCambioContrasenia(usuario, LocalDateTime.now()));

        LocalDateTime ahora = LocalDateTime.now();
        if (bloqueo.estaBloqueado(ahora)) {
            throw new CambioContraseniaBloqueadoException(bloqueo.minutosRestantes(ahora));
        }
        // Vencido el bloqueo el contador vuelve a 0: no se acumula histórico.
        if (bloqueo.getId() != null && bloqueo.getBloqueadoHasta() != null) {
            bloqueo.setIntentosFallidos(0);
            bloqueo.setBloqueadoHasta(null);
            repoBloqueoCambioContrasenia.save(bloqueo);
        }

        // Reglas de fortaleza: las resuelve @Valid en el controller (anotaciones
        // estándar sobre el DTO) ANTES de llegar acá; su fallo no cuenta como
        // intento fallido y no llega a este servicio.

        if (request.getContraseniaNueva().equals(request.getContraseniaActual())) {
            registrarIntentoFallido(bloqueo);
            throw new ContraseniaInvalidaException("La contraseña nueva debe ser distinta de la contraseña actual");
        }

        // Verificación de la contraseña actual contra Auth0 (password-realm);
        // el token devuelto se descarta.
        try {
            authService.obtenerTokenParaLogearUsuario(usuario.getCorreoElectronico(), request.getContraseniaActual());
        } catch (NoSePudoCrearUsuario e) {
            registrarIntentoFallido(bloqueo);
            throw new ContraseniaInvalidaException("La contraseña actual es incorrecta");
        }

        try {
            authService.guardarContraseniaEnAuth0(usuario.getId(), request.getContraseniaNueva());
        } catch (NoSePudoCambiarContraseniaException e) {
            registrarIntentoFallido(bloqueo);
            throw e;
        }

        // Éxito: contador a 0 y bloqueo limpiado.
        if (bloqueo.getId() != null) {
            bloqueo.setIntentosFallidos(0);
            bloqueo.setBloqueadoHasta(null);
            repoBloqueoCambioContrasenia.save(bloqueo);
        }
    }

    private void registrarIntentoFallido(BloqueoCambioContrasenia bloqueo) {
        bloqueo.setIntentosFallidos(bloqueo.getIntentosFallidos() + 1);
        if (bloqueo.getIntentosFallidos() >= maxIntentos) {
            bloqueo.setBloqueadoHasta(LocalDateTime.now().plusHours(horasBloqueo));
        }
        repoBloqueoCambioContrasenia.save(bloqueo);
        log.info("Intento fallido de cambio de contraseña autenticado: {} de {}",
                bloqueo.getIntentosFallidos(), maxIntentos);
    }
}
