package com.pretriage.backend.services;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pretriage.backend.controllers.dtos.CombinacionRutasDTO;
import com.pretriage.backend.controllers.dtos.HospitalCercanoDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoArriboHospitalResponse;
import com.pretriage.backend.model.hospitales.Coordenada;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.hospitales.Hospital;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalTime;
import java.util.List;


import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/*
* Test de integracion donde mockeo las respuestas de la api de google usando wireMockServer
* */

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { //se sobreescriben los valores del "application.properties" solo para ejecutar este test
        "google.api.key=test-api-key",
        "google.places.base-url=http://localhost:8089/v1/places",
        "google.routes.base-url=http://localhost:8089/directions/v2:computeRoutes",
        // auth0 no se usan en este test
        "auth0.client-id.machine-to-machine=AUTH0_M2M_CLIENT_ID",
        "auth0.client-secret.machine-to-machine=AUTH0_M2M_CLIENT_SECRET",
        "auth0.scope.machine-to-machine=AUTH0_M2M_SCOPE",
        "auth0.base-path=http://localhost:8089",
        "auth0.client-id.app=ATUH0_APP_CLIENT_ID"
})
public class GooglePlacesServiceTest {

    @Autowired
    private GooglePlacesService service;

    @MockitoBean
    private DireccionService direccionService;

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void beforeAll() {
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
    }

    @AfterAll
    static void afterAll() {
        wireMockServer.stop();
    }

    @Test
    void clinicasRegistradoApareceDesdeCorrientesSinDependerDeNearbySearch() {
        wireMockServer.resetRequests();
        wireMockServer.stubFor(get(urlEqualTo("/v1/places/clinicas-registrado")).willReturn(okJson("""
                {"id":"clinicas-registrado","displayName":{"text":"Hospital de Clinicas"},
                 "formattedAddress":"Av. Cordoba 2351, CABA",
                 "location":{"latitude":-34.5984007,"longitude":-58.3998358},
                 "rating":4.5,"userRatingCount":100}
                """)));
        Hospital clinicas = new Hospital(); clinicas.setPlaceId("clinicas-registrado");
        var resultado = service.buscarHospitalesRegistrados(-34.602612, -58.427962, List.of(clinicas));
        assertEquals(1, resultado.size());
        assertEquals("clinicas-registrado", resultado.getFirst().getPlaceId());
        assertEquals(4.5, resultado.getFirst().getValoracionPromedio());
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/v1/places:searchNearby")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/v1/places/clinicas-registrado")));
    }

    @Test
    void noLimitaLaListaA20HospitalesYLosOrdenaPorDistancia() {
        wireMockServer.resetRequests();
        var registrados = new java.util.ArrayList<Hospital>();
        for (int i=21; i>=0; i--) {
            String id = "registrado-" + i;
            double lat = -34.6 + i * 0.0001;
            Hospital hospital = hospitalConCoordenadas(id, lat, -58.4);
            registrados.add(hospital);
            wireMockServer.stubFor(get(urlEqualTo("/v1/places/" + id)).willReturn(okJson(
                    "{\"id\":\"" + id + "\",\"displayName\":{\"text\":\"" + id + "\"},"
                    + "\"location\":{\"latitude\":" + lat + ",\"longitude\":-58.4}}")));
        }
        var resultado = service.buscarHospitalesRegistrados(-34.6, -58.4, registrados);
        assertEquals(22, resultado.size());
        assertEquals("registrado-0", resultado.getFirst().getPlaceId());
        assertEquals("registrado-21", resultado.getLast().getPlaceId());
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/v1/places:searchNearby")));
    }

    @Test
    void excluyeHospitalFueraDelRadioSinConsultarGoogleYConservaDatosLocalesSiGoogleFalla() {
        wireMockServer.resetRequests();
        Hospital lejano = hospitalConCoordenadas("registrado-lejano", -35.0, -58.4);
        Hospital cercano = hospitalConCoordenadas("registrado-sin-google", -34.601, -58.4);
        cercano.setNombre("Hospital registrado");
        wireMockServer.stubFor(get(urlEqualTo("/v1/places/registrado-sin-google")).willReturn(serverError()));
        var resultado = service.buscarHospitalesRegistrados(-34.6, -58.4, List.of(lejano, cercano));
        assertEquals(1, resultado.size());
        assertEquals("Hospital registrado", resultado.getFirst().getNombre());
        assertNull(resultado.getFirst().getValoracionPromedio());
        wireMockServer.verify(0, getRequestedFor(urlEqualTo("/v1/places/registrado-lejano")));
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/v1/places:searchNearby")));
    }

    @Test
    void sinRegistradosNoConsultaGoogleYRechazaCoordenadasInvalidas() {
        wireMockServer.resetRequests();
        assertTrue(service.buscarHospitalesRegistrados(-34.6, -58.4, List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.buscarHospitalesRegistrados(Double.NaN, -58.4, List.of()));
        wireMockServer.verify(0, anyRequestedFor(anyUrl()));
    }

    private Hospital hospitalConCoordenadas(String placeId, double lat, double lon) {
        Coordenada coordenada = new Coordenada(); coordenada.setLatitud(lat); coordenada.setLongitud(lon);
        Direccion direccion = new Direccion(); direccion.setCoordenada(coordenada);
        Hospital hospital = new Hospital(); hospital.setPlaceId(placeId); hospital.setDireccion(direccion);
        return hospital;
    }

    @Test
    void sePuebeObtenerUnaListaDeHospitalesCercanosEnBaseALatitudLongitud(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": [
                                {
                                  "id": "hospital1",
                                  "formattedAddress": "Av Siempre Viva 123",
                                  "displayName": {
                                    "text": "Hospital Italiano"
                                  }
                                }
                              ]
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertEquals(1, hospitales.size());

        HospitalCercanoDTO hospital = hospitales.getFirst();

        assertEquals("hospital1", hospital.getPlaceId());
        assertEquals("Hospital Italiano", hospital.getNombre());
        assertEquals("Av Siempre Viva 123", hospital.getDireccion());
    }

    @Test
    void mapeaLaValoracionDeGoogleAlDtoDelHospitalCercano(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": [
                                {
                                  "id": "hospital1",
                                  "formattedAddress": "Av Siempre Viva 123",
                                  "displayName": {
                                    "text": "Hospital Italiano"
                                  },
                                  "rating": 4.5,
                                  "userRatingCount": 120
                                }
                              ]
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertEquals(1, hospitales.size());

        HospitalCercanoDTO hospital = hospitales.getFirst();

        assertEquals(4.5, hospital.getValoracionPromedio());
        assertEquals(120, hospital.getCantidadValoraciones());
    }

    @Test
    void elPuntajeQuedaNullYSeMantieneLaCantidadCuandoGoogleNoDevuelveRating(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": [
                                {
                                  "id": "hospital1",
                                  "formattedAddress": "Av Siempre Viva 123",
                                  "displayName": {
                                    "text": "Hospital Sin Resenas"
                                  },
                                  "userRatingCount": 3
                                }
                              ]
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertEquals(1, hospitales.size());

        HospitalCercanoDTO hospital = hospitales.getFirst();

        assertNull(hospital.getValoracionPromedio());
        assertEquals(3, hospital.getCantidadValoraciones());
    }

    @Test
    void losCamposDeValoracionQuedanAmbosNullCuandoGoogleNoEnviaNingunoDeLosDos(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": [
                                {
                                  "id": "hospital1",
                                  "formattedAddress": "Av Siempre Viva 123",
                                  "displayName": {
                                    "text": "Hospital Sin Resenas"
                                  }
                                }
                              ]
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertEquals(1, hospitales.size());

        HospitalCercanoDTO hospital = hospitales.getFirst();

        assertNull(hospital.getValoracionPromedio());
        assertNull(hospital.getCantidadValoraciones());
    }

    @Test
    void mantieneElPuntajeSinCantidadCuandoGoogleEnviaSoloElRating(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": [
                                {
                                  "id": "hospital1",
                                  "formattedAddress": "Av Siempre Viva 123",
                                  "displayName": {
                                    "text": "Hospital Solo Puntaje"
                                  },
                                  "rating": 4.2
                                }
                              ]
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertEquals(1, hospitales.size());

        HospitalCercanoDTO hospital = hospitales.getFirst();

        assertEquals(4.2, hospital.getValoracionPromedio());
        assertNull(hospital.getCantidadValoraciones());
    }

    @Test
    void noSePuebeObtenerUnaListaDeHospitalesCercanosEnBaseALatitudLongitudSiNoHayHospitalesEnEseRadio(){
        wireMockServer.stubFor(post(urlEqualTo("/v1/places:searchNearby"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "places": []
                            }
                            """)));

        List<HospitalCercanoDTO> hospitales =
                service.buscarHospitales(-34.6, -58.4);

        assertTrue(hospitales.isEmpty());
    }



    @Test
    void sePuedeObtenerUnHospitalDeGoogleEnBaseAlPlaceID(){


        Direccion direccion = new Direccion();

        when(direccionService.buscarOCrearDireccion(
                any(),
                any(),
                any()))
                .thenReturn(direccion);

        wireMockServer.stubFor(get(urlEqualTo("/v1/places/hospital1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "id":"hospital1",
                          "displayName":{
                            "text":"Hospital Italiano"
                          },
                          "addressComponents":[],
                          "location":{
                            "latitude":-34.6,
                            "longitude":-58.4
                          }
                        }
                        """)));

        Hospital hospital =
                service.obtenerHospitalDesdeGoogle("hospital1");

        assertNotNull(hospital);

        assertEquals("hospital1", hospital.getPlaceId());
        assertEquals("Hospital Italiano", hospital.getNombre());
        assertSame(direccion, hospital.getDireccion());
    }

    @Test
    void noSePuedeObtenerUnHospitalEnBaseAlPlaceIDSiNoExisteONoEsValido(){
        wireMockServer.stubFor(get(urlEqualTo("/v1/places/inexistente"))
                .willReturn(aResponse()
                        .withStatus(404)));

        Hospital hospital =
                service.obtenerHospitalDesdeGoogle("inexistente");

        assertNull(hospital);
    }

    @Test
    void sePuedeCalcularTiempoEstimadoArriboHospitalConExito(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                   "routes":[
                                      {
                                         "duration":"300s",
                                         "distanceMeters":1500,
                                         "polyline":{
                                            "encodedPolyline":"encoded_polyline_string"
                                         },
                                         "routeLabels":[
                                            "DEFAULT_ROUTE"
                                         ],
                                         "legs":[
                                            {
                                               "duration":"120s",
                                               "distanceMeters":148,
                                               "steps":[
                                                  {
                                                     "travelMode":"WALK",
                                                     "navigationInstruction":{
                                                        "instructions":"DirÃ­gete al hospital"
                                                     },
                                                     "distanceMeters":30,
                                                     "duration":"20s"
                                                  },
                                                  {
                                                     "travelMode":"TRANSIT",
                                                     "navigationInstruction":{
                                                        "instructions":"AutobÃºs en direcciÃ³n a Once"
                                                     },
                                                     "transitDetails":{
                                                        "transitLine":{
                                                           "name":"Linea 85 A",
                                                           "nameShort":"85 A"
                                                        }
                                                     },
                                                     "distanceMeters":130,
                                                     "duration":"100s"
                                                  }
                                               ]
                                             }
                                          ]
                                       }
                                    ]
                                }
                                """)));

        List<TiempoEstimadoArriboHospitalResponse> responseList =
                service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.61, -58.41);

        assertNotNull(responseList);
        assertEquals(1, responseList.size());

        TiempoEstimadoArriboHospitalResponse response = responseList.getFirst();
        assertEquals(1L, response.getIdHospital());
        assertEquals("transporte-publico", response.getTransporte());
        assertEquals(LocalTime.of(0, 5, 0), response.getTiempoEstimadoArribo());
        assertEquals(1500, response.getDistanciaMetros());
        assertEquals("encoded_polyline_string", response.getPolylineCode());

        List<CombinacionRutasDTO> combinaciones = response.getCombinacionesLineas();
        assertNotNull(combinaciones);
        assertEquals(2, combinaciones.size());

        CombinacionRutasDTO pasoCaminando = combinaciones.get(0);
        assertEquals("caminar", pasoCaminando.getTipoTransporte());
        assertEquals("DirÃ­gete al hospital", pasoCaminando.getIndicaciones());
        assertNull(pasoCaminando.getNombreLinea());

        CombinacionRutasDTO pasoTransportePublico = combinaciones.get(1);
        assertEquals("transporte-publico", pasoTransportePublico.getTipoTransporte());
        assertEquals("AutobÃºs en direcciÃ³n a Once", pasoTransportePublico.getIndicaciones());
        assertEquals("Linea 85 A", pasoTransportePublico.getNombreLinea());
    }

    @Test
    void fallaCalcularTiempoEstimadoArriboHospitalCuandoApiNoDevuelveRutas(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": []
                            }
                            """)));

        assertThrows(IllegalArgumentException.class, () ->
                service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.61, -58.41));
    }

    @Test
    void fallaCalcularTiempoEstimadoArriboHospitalCuandoApiDevuelveError(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(500)));

        assertThrows(IllegalArgumentException.class, () ->
                service.calcularTiempoArriboHospital(hospital, "transporte-publico", -34.61, -58.41));
    }

    @Test
    void fallaCalcularTiempoEstimadoArriboHospitalCuandoTransporteNoEsValido(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        assertThrows(IllegalArgumentException.class, () ->
                service.calcularTiempoArriboHospital(hospital, "transporte-invalido", -34.61, -58.41));
    }

    @Test
    void sePuedeCalcularTiempoEstimadoArriboHospitalConMultiplesRutas(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": [
                                {
                                  "duration": "300s",
                                  "distanceMeters": 1500,
                                  "polyline": {
                                    "encodedPolyline": "route_a"
                                  },
                                  "legs": [
                                    {
                                      "steps": [
                                        {
                                          "travelMode": "WALK",
                                          "navigationInstruction": {
                                            "instructions": "Ruta A: caminar"
                                          }
                                        }
                                      ]
                                    }
                                  ]
                                },
                                {
                                  "duration": "360s",
                                  "distanceMeters": 1900,
                                  "polyline": {
                                    "encodedPolyline": "route_b"
                                  },
                                  "legs": [
                                    {
                                      "steps": [
                                        {
                                          "travelMode": "WALK",
                                          "navigationInstruction": {
                                            "instructions": "Ruta B: caminar"
                                          }
                                        }
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                            """)));

        List<TiempoEstimadoArriboHospitalResponse> responseList =
                service.calcularTiempoArriboHospital(hospital, "caminar", -34.61, -58.41);

        assertNotNull(responseList);
        assertEquals(2, responseList.size());

        TiempoEstimadoArriboHospitalResponse rutaA = responseList.get(0);
        assertEquals(LocalTime.of(0, 5, 0), rutaA.getTiempoEstimadoArribo());
        assertEquals("route_a", rutaA.getPolylineCode());
        assertEquals(1, rutaA.getCombinacionesLineas().size());
        assertEquals("Ruta A: caminar", rutaA.getCombinacionesLineas().getFirst().getIndicaciones());

        TiempoEstimadoArriboHospitalResponse rutaB = responseList.get(1);
        assertEquals(LocalTime.of(0, 6, 0), rutaB.getTiempoEstimadoArribo());
        assertEquals("route_b", rutaB.getPolylineCode());
        assertEquals(1, rutaB.getCombinacionesLineas().size());
        assertEquals("Ruta B: caminar", rutaB.getCombinacionesLineas().getFirst().getIndicaciones());
    }

    @Test
    void sePuedeCalcularTiempoEstimadoArriboHospitalConUnaRutaSinTransportePublico(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": [
                                {
                                  "duration": "300s",
                                  "distanceMeters": 1500,
                                  "polyline": {
                                    "encodedPolyline": "encoded_polyline_string"
                                  },
                                  "legs": [
                                    {
                                      "steps": [
                                        {
                                          "travelMode": "WALK",
                                          "navigationInstruction": {
                                            "instructions": "Caminar por Av. Siempre Viva"
                                          }
                                        }
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                            """)));

        List<TiempoEstimadoArriboHospitalResponse> responseList =
                service.calcularTiempoArriboHospital(hospital, "caminar", -34.61, -58.41);

        assertNotNull(responseList);
        assertEquals(1, responseList.size());

        List<CombinacionRutasDTO> combinaciones = responseList.getFirst().getCombinacionesLineas();
        assertEquals(1, combinaciones.size());
        CombinacionRutasDTO paso = combinaciones.getFirst();
        assertEquals("caminar", paso.getTipoTransporte());
        assertEquals("Caminar por Av. Siempre Viva", paso.getIndicaciones());
        assertNull(paso.getNombreLinea());
    }

    @Test
    void sePuedeCalcularElTiempoEstimadoArriboDeLaMejorRuta(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": [
                                {
                                  "duration": "300s",
                                  "routeLabels": ["DEFAULT_ROUTE"]
                                },
                                {
                                  "duration": "200s",
                                  "routeLabels": ["FUEL_EFFICIENT"]
                                }
                              ]
                            }
                            """)));

        LocalTime tiempo =
                service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.61, -58.41);

        assertEquals(LocalTime.of(0, 5, 0), tiempo);
    }

    @Test
    void quedaNullElTiempoEstimadoDeLaMejorRutaCuandoNoHayRutaDefault(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlPathEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": [
                                {
                                  "duration": "300s",
                                  "routeLabels": ["FUEL_EFFICIENT"]
                                }
                              ]
                            }
                            """)));

        LocalTime tiempo =
                service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.61, -58.41);

        assertNull(tiempo);
    }

    @Test
    void quedaNullElTiempoEstimadoDeLaMejorRutaCuandoLaApiNoDevuelveRutas() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlPathEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "routes": []
                            }
                            """)));

        LocalTime tiempo =
                service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.61, -58.41);

        assertNull(tiempo);
    }

    @Test
    void quedaNullElTiempoEstimadoDeLaMejorRutaCuandoLaApiDevuelveError(){
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setPlaceId("hospital1");

        Coordenada coordenada = new Coordenada();
        coordenada.setLatitud(-34.6);
        coordenada.setLongitud(-58.4);

        Direccion direccion = new Direccion();
        direccion.setCoordenada(coordenada);
        hospital.setDireccion(direccion);

        wireMockServer.stubFor(post(urlPathEqualTo("/directions/v2:computeRoutes"))
                .willReturn(aResponse()
                        .withStatus(500)));

        LocalTime tiempo =
                service.calcularTiempoEstimadoArriboMejorRuta(hospital, "transporte-publico", -34.61, -58.41);

        assertNull(tiempo);
    }

    @Test
    void sePuedeVerificarTransporteValido(){
        assertTrue(service.esTransporteValido("transporte-publico"));
        assertTrue(service.esTransporteValido("vehiculo"));
        assertTrue(service.esTransporteValido("vehiculo-dos-ruedas"));
        assertTrue(service.esTransporteValido("caminar"));
        assertTrue(service.esTransporteValido("bicicleta"));
    }

    @Test
    void sePuedeVerificarTransporteInvalido(){
        assertFalse(service.esTransporteValido("transporte-invalido"));
        assertFalse(service.esTransporteValido("avion"));
        assertFalse(service.esTransporteValido(""));
    }


}
