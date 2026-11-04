package com.pretriage.backend.services;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    // 32 bytes => 43 chars base64url, suficiente entropía (256 bits)
    private static final int TOKEN_BYTES = 32;

    public String generarToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }
}
