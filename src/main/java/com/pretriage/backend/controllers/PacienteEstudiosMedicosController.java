package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.EstudioClinicoDTO;
import com.pretriage.backend.services.EstudioClinicoService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PacienteEstudiosMedicosController {

    private final EstudioClinicoService estudioClinicoService;

    @PostMapping(value = "/estudios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> subirEstudioClinico(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam("tipoArchivo") String tipoArchivo,
            @RequestParam(value = "descripcion", required = false) String descripcion) {

        EstudioClinicoDTO dto = new EstudioClinicoDTO();
        dto.setTipoArchivo(tipoArchivo);
        dto.setDescripcion(descripcion);

        estudioClinicoService.subirArchivoEstudioClinico(
                jwt.getSubject(),
                file,
                dto);

        return ResponseEntity.ok(Map.of("message", "Archivo subido exitosamente"));
    }

    @DeleteMapping("/estudios/{idEstudio}")
    public ResponseEntity<Map<String, String>> eliminarEstudioClinico(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idEstudio") Long idEstudio) {

        estudioClinicoService.eliminarArchivoEstudioClinico(jwt.getSubject(), idEstudio);

        return ResponseEntity.ok(Map.of("message", "Archivo eliminado exitosamente"));
    }

    @GetMapping("/estudios")
    public ResponseEntity<List<EstudioClinicoDTO>> obtenerTodosLosEstudiosClinicosMetadata(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(estudioClinicoService.obtenerTodosEstudiosClinicos(jwt.getSubject()));
    }

    @GetMapping("/estudios/{idEstudio}")
    public ResponseEntity<EstudioClinicoDTO> obtenerUnEstudiosClinicoMetadata(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idEstudio") Long idEstudio) {
        return ResponseEntity.ok(estudioClinicoService.obtenerEstudioClinico(jwt.getSubject(), idEstudio));
    }

    @GetMapping("/estudios/{idEstudio}/file")
    public ResponseEntity<Resource> descargarEstudiosClinico(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("idEstudio") Long idEstudio) {

        byte[] fileToDownload = estudioClinicoService.descargarEstudioClinico(jwt.getSubject(), idEstudio);

        Resource fileResource = new ByteArrayResource(fileToDownload);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"estudioClinico\"")
                .body(fileResource);
    }
}
