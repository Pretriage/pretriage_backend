package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.controllers.dtos.CredencialResponse;
import com.pretriage.backend.services.CredencialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pacientes")
@RequiredArgsConstructor
public class ObraSocialRecepcionistaController {

    private final CredencialService credencialService;


    @GetMapping("/{idPaciente}/obrasocial/credenciales")
    public ResponseEntity<List<CredencialResponse>> obtenerCredencialesPacienteRecepcionista(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idPaciente") Long idPaciente
    ){
        return ResponseEntity.ok(
                credencialService.obtenerCredencialesPacienteRecepcionista(jwt.getSubject(), idPaciente));
    }

    @PostMapping("/{idPaciente}/obrasocial/credencial")
    public ResponseEntity<Map<String, String>> cargarCredencialRecepcionista(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idPaciente") Long idPaciente,
            @Valid @RequestBody CredencialRequest request
    ){

        credencialService.cargarCredencialRecepcionista(jwt.getSubject(), idPaciente, request);

        return ResponseEntity.ok(Map.of("mensaje", "credencial cargada con éxito"));
    }
    @DeleteMapping("/{idPaciente}/obrasocial/credenciales/{idCredencial}")
    public ResponseEntity<Map<String, String>> eliminarCredencialRecepcionista(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idPaciente") Long idPaciente,
            @PathVariable("idCredencial") Long idCredencial
    ){

        credencialService.eliminarCredencialRecepcionista(idCredencial, idPaciente, jwt.getSubject());

        return ResponseEntity.ok(Map.of("mensaje", "credencial eliminada con éxito"));
    }
    @PutMapping("/{idPaciente}/obrasocial/credenciales/{idCredencial}")
    public ResponseEntity<Map<String, String>> editarCredencialRecepcionista(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idPaciente") Long idPaciente,
            @PathVariable("idCredencial") Long idCredencial,
            @Valid @RequestBody CredencialRequest request
    ){

        credencialService.editarCredencialRecepcionista(idCredencial, idPaciente, jwt.getSubject(), request);

        return ResponseEntity.ok(Map.of("mensaje", "credencial actualizada con éxito"));
    }
}
