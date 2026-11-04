package com.pretriage.backend.controllers.dtos.auth0;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthIdTokenResponse {
    @JsonProperty("id_token")
    private String idToken;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("expires_in")
    private String expiresIn;

}
