package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotNull;

public record IniciarSesionRecepcionRequest(@NotNull Long hospitalId) {}
