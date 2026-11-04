package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.LoginRequest;
import com.pretriage.backend.controllers.dtos.LoginResponseDTO;
import com.pretriage.backend.controllers.dtos.RefreshTokenRequest;
import com.pretriage.backend.controllers.dtos.RegisterRequest;
import com.pretriage.backend.controllers.dtos.TipoUsuario;
import com.pretriage.backend.model.personas.*;
import com.pretriage.backend.repositories.RepoMedico;
import com.pretriage.backend.repositories.RepoPacientes;
import com.pretriage.backend.repositories.RepoRecepcionistas;
import com.pretriage.backend.services.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    private final RepoRecepcionistas repoRecepcionistas;
    private final RepoMedico repoMedico;
    private final RepoPacientes repoPacientes;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest request) {

        if (request.getTipoUsuario() != TipoUsuario.Paciente || request.getRol() != RolSistema.USER) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "El registro público sólo permite crear cuentas de paciente");
        }

        String auth0Id = authService.registrarUsuarioYObtenerAuth0Id(request.getEmail(), request.getPassword());

        String email = request.getEmail();

        this.crearPaciente(request, email, auth0Id);

        return ResponseEntity.ok(Map.of("message", "usuario creado con éxito"));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(
            @Valid @RequestBody LoginRequest request) {

        LoginResponseDTO response = authService.obtenerTokenParaLogearUsuario(request.getEmail(),
                request.getPassword());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/renovar")
    public ResponseEntity<LoginResponseDTO> renovar(
            @Valid @RequestBody RefreshTokenRequest request) {

        LoginResponseDTO response = authService.renovarTokenUsuario(request.getRefreshToken());

        return ResponseEntity.ok(response);
    }

    private UsuarioAuth crearUsuario(RegisterRequest request, String email, String auth0Id, RolSistema rol) {
        UsuarioAuth usuarioAuth = new UsuarioAuth();

        usuarioAuth.setNombre(request.getNombre());

        usuarioAuth.setApellido(request.getApellido());

        usuarioAuth.setNumeroDocumento(request.getNumeroDocumento());

        usuarioAuth.setTipoDocumento(request.getTipoDocumento());

        usuarioAuth.setCorreoElectronico(email);

        usuarioAuth.setId(auth0Id);

        usuarioAuth.setRol(rol);

        return usuarioAuth;
    }

    private void crearRecepcionista(RegisterRequest request, String email, String auth0Id, RolSistema rol) {
        Recepcionista recepcionista = new Recepcionista();

        UsuarioAuth usuarioAuth = this.crearUsuario(request, email, auth0Id, rol);

        recepcionista.setUsuarioAuth(usuarioAuth);

        repoRecepcionistas.save(recepcionista);
    }

    private void crearPaciente(RegisterRequest request, String email, String auth0Id) {
        Paciente paciente = new Paciente();

        UsuarioAuth usuarioAuth = this.crearUsuario(request, email, auth0Id, RolSistema.USER);

        paciente.setUsuarioAuth(usuarioAuth);

        paciente.setNombre(request.getNombre());
        paciente.setApellido(request.getApellido());
        paciente.setCorreoElectronico(request.getEmail());
        paciente.setNumeroDocumento(request.getNumeroDocumento());
        paciente.setTipoDocumento(request.getTipoDocumento());

        repoPacientes.save(paciente);
    }

    private void crearMedico(RegisterRequest request, String email, String auth0Id) {
        Medico medico = new Medico();

        UsuarioAuth usuarioAuth = this.crearUsuario(request, email, auth0Id, RolSistema.USER);

        medico.setMatricula(
                request.getMatricula());

        medico.setUsuarioAuth(usuarioAuth);

        repoMedico.save(medico);
    }
}
