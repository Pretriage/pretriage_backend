package com.pretriage.backend.services;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pretriage.backend.model.hospitales.Hospital;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
