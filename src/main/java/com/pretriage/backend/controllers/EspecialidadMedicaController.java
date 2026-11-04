package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.EspecialidadMedicaDTO;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.services.UsuariosService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class EspecialidadMedicaController {

    private final RepoEspecialidadesMedicas repoEspecialidadesMedicas;
    private final UsuariosService usuariosService;

    @GetMapping("/api/especialidades")
    public ResponseEntity<List<EspecialidadMedicaDTO>> obtenerEspecialidades(
            @AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();

        usuariosService.validarSiEsUsuarioValido(auth0Id);

        List<EspecialidadMedicaDTO> especialidades = repoEspecialidadesMedicas.findAll().stream()
                .sorted(Comparator.comparing(EspecialidadMedica::getNombre))
                .map(this::mapearADTO)
                .toList();

        return ResponseEntity.ok(especialidades);
    }

    private EspecialidadMedicaDTO mapearADTO(EspecialidadMedica especialidad) {
        EspecialidadMedicaDTO dto = new EspecialidadMedicaDTO();
        dto.setCodigo(especialidad.getCodigo());
        dto.setNombre(especialidad.getNombre());
        return dto;
    }
}
