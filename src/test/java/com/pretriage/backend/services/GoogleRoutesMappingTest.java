package com.pretriage.backend.services;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pretriage.backend.model.hospitales.Hospital;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class GoogleRoutesMappingTest {
    @Test
    void conservaLaRutaCuandoGoogleOmiteLaInstruccionDeUnPaso() {
        WireMockServer google = new WireMockServer(wireMockConfig().dynamicPort());
        google.start();
        try {
            google.stubFor(post(urlEqualTo("/directions/v2:computeRoutes")).willReturn(okJson("""
                    {
                      "routes": [{
                        "duration": "600s",
                        "distanceMeters": 800,
                        "polyline": {"encodedPolyline": "ruta-de-prueba"},
                        "routeLabels": ["DEFAULT_ROUTE"],
                        "legs": [{"steps": [
                          {"travelMode": "WALK"},
                          {"travelMode": "WALK", "navigationInstruction": {"instructions": "Seguí derecho"}}
                        ]}]
                      }]
                    }
                    """)));

            GooglePlacesService service = new GooglePlacesService(mock(DireccionService.class));
            ReflectionTestUtils.setField(service, "apiKey", "clave-de-prueba");
            ReflectionTestUtils.setField(service, "ROUTES_BASE_URL", google.baseUrl() + "/directions/v2:computeRoutes");
            Hospital hospital = new Hospital();
            hospital.setId(1L);
            hospital.setPlaceId("hospital-de-prueba");

            var rutas = service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.6, -58.4);

            assertEquals(1, rutas.size());
            var ruta = rutas.getFirst();
            assertEquals("ruta-de-prueba", ruta.getPolylineCode());
            assertEquals(2, ruta.getCombinacionesLineas().size());
            assertEquals("", ruta.getCombinacionesLineas().getFirst().getIndicaciones());
            assertEquals("Seguí derecho", ruta.getCombinacionesLineas().getLast().getIndicaciones());
        } finally {
            google.stop();
        }
    }

    @Test
    void avisaQueGoogleLimitoLasConsultasCuandoRespondeTooManyRequests() {
        WireMockServer google = new WireMockServer(wireMockConfig().dynamicPort());
        google.start();
        try {
            google.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                    .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"code\":429}}")));

            GooglePlacesService service = new GooglePlacesService(mock(DireccionService.class));
            ReflectionTestUtils.setField(service, "apiKey", "clave-de-prueba");
            ReflectionTestUtils.setField(service, "ROUTES_BASE_URL", google.baseUrl() + "/directions/v2:computeRoutes");
            Hospital hospital = new Hospital();
            hospital.setId(1L);
            hospital.setPlaceId("hospital-de-prueba");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.6, -58.4));

            assertEquals(GooglePlacesService.MENSAJE_LIMITE_RUTAS_GOOGLE, error.getMessage());
        } finally {
            google.stop();
        }
    }

    @Test
    void reutilizaLaRutaCalculadaParaElMismoOrigenYNoVuelveAConsultarAGoogle() {
        WireMockServer google = new WireMockServer(wireMockConfig().dynamicPort());
        google.start();
        try {
            google.stubFor(post(urlEqualTo("/directions/v2:computeRoutes")).willReturn(okJson("""
                    {"routes": [{"duration": "600s", "distanceMeters": 800,
                      "polyline": {"encodedPolyline": "ruta"}, "routeLabels": ["DEFAULT_ROUTE"],
                      "legs": [{"steps": [{"travelMode": "WALK"}]}]}]}
                    """)));
            GooglePlacesService service = servicioContra(google);
            ReflectionTestUtils.setField(service, "segundosCacheRutas", 300L);
            Hospital hospital = hospitalDePrueba();

            service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.60001, -58.40001);
            var tiempo = service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.60002, -58.40002);
            service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.6, -58.4);
            service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.6, -58.4);

            assertEquals(java.time.LocalTime.of(0, 10, 0), tiempo);
            google.verify(2, postRequestedFor(urlEqualTo("/directions/v2:computeRoutes")));
        } finally {
            google.stop();
        }
    }

    @Test
    void despuesDeUn429DejaDeConsultarRutasPorUnRato() {
        WireMockServer google = new WireMockServer(wireMockConfig().dynamicPort());
        google.start();
        try {
            google.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                    .willReturn(aResponse().withStatus(429).withBody("{}")));
            GooglePlacesService service = servicioContra(google);
            Hospital hospital = hospitalDePrueba();

            assertEquals(null, service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.6, -58.4));
            assertEquals(null, service.calcularTiempoEstimadoArriboMejorRuta(hospital, "caminar", -34.6, -58.4));
            assertThrows(IllegalArgumentException.class,
                    () -> service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.6, -58.4));

            google.verify(1, postRequestedFor(urlEqualTo("/directions/v2:computeRoutes")));
        } finally {
            google.stop();
        }
    }

    private static GooglePlacesService servicioContra(WireMockServer google) {
        GooglePlacesService service = new GooglePlacesService(mock(DireccionService.class));
        ReflectionTestUtils.setField(service, "apiKey", "clave-de-prueba");
        ReflectionTestUtils.setField(service, "ROUTES_BASE_URL", google.baseUrl() + "/directions/v2:computeRoutes");
        return service;
    }

    private static Hospital hospitalDePrueba() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital-de-prueba");
        return hospital;
    }
}
