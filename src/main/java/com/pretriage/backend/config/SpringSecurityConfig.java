package com.pretriage.backend.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Configuration
public class SpringSecurityConfig {

        private static final Pattern RUTA_CHAT_VOZ = Pattern.compile("^/api/chat/[^/]+/voz/?$");

        @Bean
        public SecurityFilterChain mainConfig(HttpSecurity http) {
                http.csrf(Customizer.withDefaults())
                                .cors(Customizer.withDefaults())
                                .csrf(AbstractHttpConfigurer::disable)
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests((authorize) -> authorize
                                                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)
                                                .permitAll()
                                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                                .requestMatchers("/api/login", "/api/register", "/api/renovar",
                                                "/api/auth/cambio-contrasenia/solicitar-token",
                                                "/api/auth/cambio-contrasenia/validar",
                                                "/api/auth/cambio-contrasenia")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/invitaciones/*/resumen")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.POST, "/api/invitaciones/*/registro")
                                                .permitAll()
                                                .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                                                "/v3/api-docs/**")
                                                .permitAll()
                                                .requestMatchers("/actuator/health", "/actuator/health/**")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource(
                        @Value("${pretriage.cors.allowed-origins:}") String[] configuredOrigins,
                        @Value("${pretriage.voz.origenes-permitidos:*}") String[] configuredVoiceOrigins) {
                List<String> origins = Arrays.stream(configuredOrigins)
                                .map(String::trim)
                                .filter(origin -> !origin.isEmpty())
                                .toList();
                if (origins.contains("*") || origins.stream().anyMatch(origin -> origin.contains("*"))) {
                        throw new IllegalArgumentException(
                                        "CORS origins must be explicit origins; wildcard origins are not permitted");
                }

                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(origins);
                configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin"));

                List<String> voiceOrigins = Arrays.stream(configuredVoiceOrigins)
                                .map(String::trim)
                                .filter(origin -> !origin.isEmpty())
                                .toList();
                CorsConfiguration voiceConfiguration = new CorsConfiguration();
                voiceConfiguration.setAllowedOriginPatterns(voiceOrigins);
                voiceConfiguration.setAllowedMethods(List.of("GET"));
                voiceConfiguration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin"));

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/api/chat/*/voz", voiceConfiguration);
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }

        /**
         * Los navegadores no pueden enviar Authorization en el handshake WebSocket, por eso
         * solo el chat de voz acepta el token en {@code ?access_token=}.
         */
        @Bean
        public BearerTokenResolver bearerTokenResolver() {
                DefaultBearerTokenResolver porHeader = new DefaultBearerTokenResolver();
                DefaultBearerTokenResolver conQueryParam = new DefaultBearerTokenResolver();
                conQueryParam.setAllowUriQueryParameter(true);
                return request -> RUTA_CHAT_VOZ.matcher(request.getRequestURI()).matches()
                                && "GET".equals(request.getMethod())
                                                ? conQueryParam.resolve(request)
                                                : porHeader.resolve(request);
        }

        @Bean
        public JwtDecoder jwtDecoder(
                        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
                SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                requestFactory.setProxy(Proxy.NO_PROXY);
                requestFactory.setConnectTimeout(10_000);
                requestFactory.setReadTimeout(10_000);

                RestTemplate restTemplate = new RestTemplate(requestFactory);
                String jwkSetUri = issuerUri.endsWith("/")
                                ? issuerUri + ".well-known/jwks.json"
                                : issuerUri + "/.well-known/jwks.json";

                NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                                .restOperations(restTemplate)
                                .build();
                jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
                return jwtDecoder;
        }

}
