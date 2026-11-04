package com.pretriage.backend.controllers.dtos.auth0;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthRegisterTokenYUserId {

    private String token;
    private String auth0Id;

}
