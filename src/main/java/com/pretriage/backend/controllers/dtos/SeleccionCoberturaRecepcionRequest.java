package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.Positive;

public record SeleccionCoberturaRecepcionRequest(@Positive Long credencialId) {}
