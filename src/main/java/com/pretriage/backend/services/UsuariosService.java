package com.pretriage.backend.services;

import com.pretriage.backend.repositories.RepoUsuariosAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsuariosService {

    private final RepoUsuariosAuth repoUsuariosAuth;

    public void validarSiEsUsuarioValido(String auth0Id) {
        repoUsuariosAuth.findById(auth0Id)
                .orElseThrow(() -> new AccessDeniedException("Usuario no encontrado o no valido"));
    }
}
