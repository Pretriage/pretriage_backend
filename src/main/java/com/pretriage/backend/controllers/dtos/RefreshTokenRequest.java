package com.pretriage.backend.controllers.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshTokenRequest {

    @NotBlank(message = "es obligatorio el uso de refreshToken para renovar el acceso")
    private String refreshToken;
}
