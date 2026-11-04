package com.pretriage.backend.controllers.dtos.auth0;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthUserDetailsResponse {

    @JsonProperty("user_id")
    private String userId;//auth0Id

    /* ejemplo de respuesta, en este caso solo tomamos userId
    {
	"user_id": "",
	"created_at": "2026-06-17T22:42:09.866Z",
	"email": "usuario@ejemplo.com",
	"email_verified": false,
	"identities": [
		{
			"connection": "Username-Password-Authentication",
			"user_id": "",
			"provider": "auth0",
			"isSocial": false
		}
	],
	"name": "usuario@ejemplo.com",
	"nickname": "usuario",
	"picture": "...png",
	"updated_at": "2026-06-17T22:42:09.867Z"
    }
    * */

}
