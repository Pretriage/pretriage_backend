package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DolorReportadoRequest(
        @NotBlank String localizacion,
        @NotNull @Min(0) @Max(10) Integer intensidad) {}
