package com.pretriage.backend.controllers.dtos.auth0;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

}
