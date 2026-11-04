package com.pretriage.backend.controllers.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponseDTO {

    private String token;
    private String refreshToken;
    private Long renovarTokenEn;
}
