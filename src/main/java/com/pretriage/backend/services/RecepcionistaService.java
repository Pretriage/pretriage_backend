package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.RecepcionistaNoExisteException;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoRecepcionistas;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecepcionistaService {

    private final RepoRecepcionistas repoRecepcionistas;

    public boolean esRecepcionistaConUsuarioId(String idUsuario){
        return repoRecepcionistas
                .existsByUsuarioAuthId(idUsuario);
    }

    public UsuarioAuth obtenerUsuarioAuth(String idUsuario){
        return repoRecepcionistas.findByUsuarioAuthId(idUsuario)
                .orElseThrow(RecepcionistaNoExisteException::new);
    }
}
