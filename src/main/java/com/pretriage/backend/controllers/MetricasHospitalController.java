package com.pretriage.backend.controllers;

import com.pretriage.backend.controllers.dtos.metricas.MetricasHospitalResponse;
import com.pretriage.backend.services.MetricasHospitalService;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/hospitales")
@Validated 
public class MetricasHospitalController {
    private final MetricasHospitalService service;

    @GetMapping("/{hospitalId}/metricas")
    public MetricasHospitalResponse obtenerMetricas(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Past(message = "La fecha 'desde' debe ser anterior a la fecha actual")
            LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @PastOrPresent (message = "La fecha 'hasta' debe ser anterior o igual a la fecha actual")
            LocalDate hasta) {
        return service.obtenerMetricas(jwt.getSubject(), hospitalId, desde, hasta);
    }
}
