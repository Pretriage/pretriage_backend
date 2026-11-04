package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.HospitalCercanoDTO;
import com.pretriage.backend.controllers.dtos.HospitalSeleccionadoResponse;
import com.pretriage.backend.controllers.dtos.SeleccionHospitalRequest;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoArriboHospitalResponse;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.services.AtencionHospitalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class HospitalController {

        private final AtencionHospitalService atencionHospitalService;

        /*
         * ordenarPor define el criterio de orden del resultado (valores validos en
         * ORDENES_VALIDOS, case-sensitive, combinados con |):
         * - "distancia" (default) conserva el orden de proximidad de Google Places
         * - "tiempo-atencion" ordena por menor tiempo estimado de espera en cola
         * - "valoracion" ordena por mayor valoracion de Google Places (null = 0,
         * van al final); desempata por cantidad de valoraciones y luego por nombre
         * - "distancia|tiempo-atencion" (o cualquier combinacion, ej.
         * "distancia|tiempo-atencion|valoracion") ordena por suma de rankings
         */
        @GetMapping("/api/hospitales/cercanos")
        public ResponseEntity<List<HospitalCercanoDTO>> obtenerHospitalesCercanos(
                        @RequestParam Double latitud,
                        @RequestParam Double longitud,
                        @RequestParam String codigoEspecialidad,
                        @RequestParam(required = false, defaultValue = "transporte-publico") String transporte,
                        @RequestParam(required = false, defaultValue = "distancia") String ordenarPor,
                        @AuthenticationPrincipal Jwt jwt) {
                return ResponseEntity.ok(atencionHospitalService
                                .buscarHospitalesCercanos(latitud, longitud, codigoEspecialidad, transporte,
                                                jwt.getSubject(), ordenarPor));
        }

        @PostMapping("/api/atencion/hospital")
        public ResponseEntity<Void> seleccionarHospital(
                        @AuthenticationPrincipal Jwt jwt,
                        @RequestBody @Valid SeleccionHospitalRequest request) {
                String auth0Id = jwt.getSubject();

                atencionHospitalService.seleccionarHospital(
                                auth0Id,
                                request.getPlaceId(),
                                request.getCodigoEspecialidad());
                return ResponseEntity.noContent().build();
        }

        @GetMapping("/api/atencion/hospital")
        public ResponseEntity<HospitalSeleccionadoResponse> obtenerHospitalSeleccionado(
                        @AuthenticationPrincipal Jwt jwt) {
                String auth0Id = jwt.getSubject();
                return ResponseEntity.ok(atencionHospitalService.obtenerHospitalSeleccionado(auth0Id));
        }

        @GetMapping("/api/atencion/tiempo-estimado")
        public ResponseEntity<TiempoEstimadoAtencionResponse> obtenerTiempoEstimadoDeAtencion(
                        @AuthenticationPrincipal Jwt jwt) {
                String auth0Id = jwt.getSubject();
                return ResponseEntity.ok(atencionHospitalService.obtenerTiempoEstimadoDeAtencion(auth0Id));
        }

        @GetMapping("/api/hospitales/{idHospital}/tiempo-arribo")
        public ResponseEntity<List<TiempoEstimadoArriboHospitalResponse>> calcularTiempoArriboAlHospital(
                        @PathVariable Long idHospital,
                        @AuthenticationPrincipal Jwt jwt,
                        @RequestParam String transporte, // obligatorio
                        @RequestParam Double latitud, // obligatorio
                        @RequestParam Double longitud // obligatorio
        ) {
                return ResponseEntity.ok(atencionHospitalService
                                .calcularTiempoArriboHospital(jwt.getSubject(), idHospital, transporte, latitud,
                                                longitud));
        }
}
