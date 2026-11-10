package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.DatosClinicosConsultaDTO;
import com.pretriage.backend.exceptions.ArchivoS3Exception;
import com.pretriage.backend.services.DatosClinicosConsultaService;
import com.pretriage.backend.services.EstudioClinicoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/datos-clinicos")
@RequiredArgsConstructor
public class DatosClinicosConsultaController {

    private final DatosClinicosConsultaService datosClinicosConsultaService;

    @GetMapping
    public ResponseEntity<DatosClinicosConsultaDTO> obtenerDatosClinicos(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long sesionId, @PathVariable Long consultaId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(datosClinicosConsultaService.obtenerDatos(jwt.getSubject(), sesionId, consultaId));
    }

    @GetMapping("/estudios/{estudioId}/archivo")
    public ResponseEntity<byte[]> obtenerArchivo(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sesionId,
            @PathVariable Long consultaId,
            @PathVariable Long estudioId) {
        EstudioClinicoService.Archivo archivo = datosClinicosConsultaService.obtenerArchivo(
                jwt.getSubject(), sesionId, consultaId, estudioId);
        MediaType tipo = tipoPrevisualizacion(archivo.extension());
        ContentDisposition.Builder disposicion = MediaType.APPLICATION_OCTET_STREAM.equals(tipo)
                ? ContentDisposition.attachment() : ContentDisposition.inline();
        String nombre = archivo.nombre() == null || archivo.nombre().isBlank()
                ? "estudio_medico_" + estudioId : archivo.nombre();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposicion.filename(nombre, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(archivo.contenido());
    }

    @ExceptionHandler(ArchivoS3Exception.class)
    ResponseEntity<Map<String, String>> archivoNoDisponible() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "El archivo clínico no está disponible en este momento."));
    }

    private MediaType tipoPrevisualizacion(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
