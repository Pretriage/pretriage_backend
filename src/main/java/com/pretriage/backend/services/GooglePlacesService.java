package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CombinacionRutasDTO;
import com.pretriage.backend.controllers.dtos.HospitalCercanoDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoArriboHospitalResponse;
import com.pretriage.backend.controllers.dtos.googleMaps.*;
import com.pretriage.backend.model.hospitales.Coordenada;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.hospitales.Hospital;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GooglePlacesService {

    private final DireccionService direccionService;
    /**
     * Limites de espera para Google: sin ellos, una respuesta lenta de Google
     * bloquea la solicitud del paciente indefinidamente.
     */
    static final Duration TIMEOUT_CONEXION_GOOGLE = Duration.ofSeconds(3);
    static final Duration TIMEOUT_LECTURA_GOOGLE = Duration.ofSeconds(8);

    /** Ejecuta en paralelo las consultas independientes a Google (una por hospital). */
    static final ExecutorService EJECUTOR_GOOGLE = Executors.newVirtualThreadPerTaskExecutor();

    private final RestClient restClient = crearClienteGoogle();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Base URL de la Places API (New)
    @Value("${google.places.base-url}")
    private String PLACES_BASE_URL;

    @Value("${google.routes.base-url}")
    private String ROUTES_BASE_URL;

    // Radio de búsqueda en metros (5 km). Ajustar según necesidad.
    private static final double RADIO_BUSQUEDA_METROS = 5000.0;

    // Máximo de resultados por búsqueda (límite de la API: 20)
    private static final int MAX_RESULTADOS = 20;

    private static final Map<String, TravelMode> TRANSPORTES_PERMITIDOS_MAP = new HashMap<>( // busqueda mas rapida con
                                                                                             // un
            // HASH MAP O(1)
            Map.of(
                    "transporte-publico", TravelMode.TRANSIT,
                    "vehiculo", TravelMode.DRIVE,
                    "vehiculo-dos-ruedas", TravelMode.TWO_WHEELER,
                    "caminar", TravelMode.WALK,
                    "bicicleta", TravelMode.BICYCLE));
    /**
     * Campo mask para Nearby Search.
     * id, displayName, formattedAddress, location, types disparan el SKU
     * "Nearby Search Pro"; rating y userRatingCount suben toda la llamada al SKU
     * "Nearby Search Enterprise" (se factura el SKU mas alto del mask).
     * Enterprise incluye 1.000 requests gratis/mes (verificado 2026-10 contra
     * https://developers.google.com/maps/documentation/places/web-service/nearby-search
     * y https://developers.google.com/maps/billing-and-pricing/sku-details).
     * Ref:
     * https://developers.google.com/maps/documentation/places/web-service/nearby-search#fieldmask
     */
    private static final String NEARBY_FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.rating,places.userRatingCount";

    /**
     * Campo mask para Place Details.
     * - formattedAddress, location, addressComponents, types → Essentials SKU
     * - displayName → sube al tier Pro SKU → 5.000 gratis/mes
     * Si se quiere máximo ahorro, eliminar displayName y quedarse solo con
     * Essentials (10.000 gratis/mes).
     * Ref:
     * https://developers.google.com/maps/documentation/places/web-service/place-details#fieldmask
     */
    private static final String DETAILS_FIELD_MASK = "id,displayName,formattedAddress,location,addressComponents,types";

    /**
     * Campo mask para Compute Routes.
     * Todos estos campos disparan el SKU "Routes: Compute Routes Essentials" →
     * 10.000 gratis/mes.
     * Ref:
     * https://developers.google.com/maps/documentation/routes/web-service/compute-routes#fieldmask
     */
    private static final String COMPUTE_ROUTES_FIELD_MASK = "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.routeLabels,routes.legs";

    @Value("${google.api.key}")
    private String apiKey;

    /**
     * Usa el cliente HTTP/1.1 clasico: el cliente de Java intenta pasar cada POST
     * a HTTP/2 (h2c) y eso rompe las respuestas de servidores que no lo soportan.
     */
    private static RestClient crearClienteGoogle() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(TIMEOUT_CONEXION_GOOGLE);
        fabrica.setReadTimeout(TIMEOUT_LECTURA_GOOGLE);
        return RestClient.builder().requestFactory(fabrica).build();
    }

    /** Registered hospitals are the candidate set; Google never discovers candidates for this flow. */
    public List<HospitalCercanoDTO> buscarHospitalesRegistrados(Double latitud, Double longitud,
                                                               List<Hospital> registrados) {
        if (!coordenadasValidas(latitud, longitud)) throw new IllegalArgumentException("Coordenadas invalidas");
        List<Hospital> candidatos = new ArrayList<>();
        for (Hospital hospital : registrados) {
            if (hospital.getPlaceId() == null || hospital.getPlaceId().isBlank()) continue;
            Coordenada coordenada = hospital.getDireccion() == null ? null : hospital.getDireccion().getCoordenada();
            Double lat = coordenada == null ? null : coordenada.getLatitud();
            Double lon = coordenada == null ? null : coordenada.getLongitud();
            if (coordenadasValidas(lat, lon) && distanciaMetros(latitud, longitud, lat, lon) > RADIO_BUSQUEDA_METROS) continue;
            candidatos.add(hospital);
        }

        // Los detalles de cada hospital se piden a Google en paralelo en lugar de uno por uno.
        Map<String, CompletableFuture<GooglePlaceDTO>> detalles = new HashMap<>();
        for (Hospital hospital : candidatos) {
            detalles.computeIfAbsent(hospital.getPlaceId(), placeId -> CompletableFuture
                    .supplyAsync(() -> obtenerDetalleCercano(placeId), EJECUTOR_GOOGLE)
                    .exceptionally(error -> null));
        }

        List<HospitalUbicado> cercanos = new ArrayList<>();
        for (Hospital hospital : candidatos) {
            Coordenada coordenada = hospital.getDireccion() == null ? null : hospital.getDireccion().getCoordenada();
            Double lat = coordenada == null ? null : coordenada.getLatitud();
            Double lon = coordenada == null ? null : coordenada.getLongitud();

            GooglePlaceDTO detalle = detalles.get(hospital.getPlaceId()).join();
            if (detalle != null && detalle.getLocation() != null
                    && coordenadasValidas(detalle.getLocation().getLatitude(), detalle.getLocation().getLongitude())) {
                lat = detalle.getLocation().getLatitude();
                lon = detalle.getLocation().getLongitude();
            }
            if (!coordenadasValidas(lat, lon)) continue;
            double distancia = distanciaMetros(latitud, longitud, lat, lon);
            if (distancia > RADIO_BUSQUEDA_METROS) continue;
            HospitalCercanoDTO dto = detalle != null ? mapearAHospitalCercanoDTO(detalle) : new HospitalCercanoDTO();
            dto.setPlaceId(hospital.getPlaceId());
            dto.setIdHospital(hospital.getId());
            if (detalle == null) {
                dto.setNombre(hospital.getNombre());
                dto.setDireccion(hospital.getDireccion() == null ? null : hospital.getDireccion().formateada());
            }
            cercanos.add(new HospitalUbicado(dto, distancia));
        }
        return cercanos.stream().sorted(Comparator.comparingDouble(HospitalUbicado::distancia))
                .map(HospitalUbicado::hospital).toList();
    }

    private GooglePlaceDTO obtenerDetalleCercano(String placeId) {
        try {
            String json = restClient.get().uri(PLACES_BASE_URL + "/{id}", placeId)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", "id,displayName,formattedAddress,location,rating,userRatingCount")
                    .retrieve().body(String.class);
            return objectMapper.readValue(json, GooglePlaceDTO.class);
        } catch (Exception error) {
            log.warn("No se pudieron completar detalles del hospital registrado placeId={} reason={}",
                    placeId, error.getClass().getSimpleName());
            return null;
        }
    }

    private boolean coordenadasValidas(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private double distanciaMetros(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
        return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }

    private record HospitalUbicado(HospitalCercanoDTO hospital, double distancia) { }

    public List<HospitalCercanoDTO> buscarHospitales(Double latitud, Double longitud) {

        // Body del POST según la documentación oficial de Nearby Search (New)
        // https://developers.google.com/maps/documentation/places/web-service/nearby-search
        Map<String, Object> requestBody = Map.of(
                "includedTypes", List.of("hospital"),
                "maxResultCount", MAX_RESULTADOS,
                "rankPreference", "DISTANCE", // ordena por distancia, no popularidad
                "locationRestriction", Map.of(
                        "circle", Map.of(
                                "center", Map.of(
                                        "latitude", latitud,
                                        "longitude", longitud),
                                "radius", RADIO_BUSQUEDA_METROS)));

        try {
            String responseJson = restClient.post()
                    .uri(PLACES_BASE_URL + ":searchNearby")
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    // El field mask va en header para controlar qué datos devuelve la API
                    // y así no disparar SKUs más caros innecesariamente.
                    .header("X-Goog-FieldMask", NEARBY_FIELD_MASK)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            GooglePlacesNearbyResponseDTO response = objectMapper.readValue(responseJson,
                    GooglePlacesNearbyResponseDTO.class);

            if (response == null || response.getPlaces() == null) {
                log.warn("La API de Google no devolvió resultados para lat={}, lng={}", latitud, longitud);
                return Collections.emptyList();
            }

            return response.getPlaces().stream()
                    .map(this::mapearAHospitalCercanoDTO)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error al buscar hospitales cercanos en Google Places API: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Convierte un {@link GooglePlaceDTO} devuelto por la API
     * a un {@link HospitalCercanoDTO} del dominio de la aplicación.
     */
    private HospitalCercanoDTO mapearAHospitalCercanoDTO(
            GooglePlaceDTO place) {

        String nombre = (place.getDisplayName() != null)
                ? place.getDisplayName().getText()
                : "Nombre no disponible";

        /*
         * //comento porque no se usa pero lo dejo por las dudas
         * Double lat = null;
         * Double lng = null;
         * if (place.getLocation() != null) {
         * lat = place.getLocation().getLatitude();
         * lng = place.getLocation().getLongitude();
         * }
         */

        HospitalCercanoDTO hospitalCercanoDTO = new HospitalCercanoDTO();
        hospitalCercanoDTO.setDireccion(place.getFormattedAddress());
        hospitalCercanoDTO.setPlaceId(place.getId());
        hospitalCercanoDTO.setNombre(nombre);
        hospitalCercanoDTO.setValoracionPromedio(place.getRating());
        hospitalCercanoDTO.setCantidadValoraciones(place.getUserRatingCount());

        return hospitalCercanoDTO;
    }

    /**
     * Obtiene los detalles de un hospital a partir de su Google Place ID,
     * usando Place Details (New).
     *
     * GET https://places.googleapis.com/v1/places/{placeId}
     *
     * @param placeId el identificador único del lugar en Google (ej:
     *                "ChIJN1t_tDeuEmsRUsoyG83frY4")
     * @return un {@link Hospital} con los datos del lugar, o {@code null} si ocurre
     *         un error
     */
    public Hospital obtenerHospitalDesdeGoogle(String placeId) {

        try {
            // La URL es: GET /v1/places/{placeId}?fields=...&key=...
            // Según la documentación, el placeId va en el path, no en el body.
            // https://developers.google.com/maps/documentation/places/web-service/place-details
            String responseJson = restClient.get()
                    .uri(PLACES_BASE_URL + "/" + placeId)
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", DETAILS_FIELD_MASK)
                    .retrieve()
                    .body(String.class);

            GooglePlaceDetailsResponseDTO detalles = objectMapper.readValue(responseJson,
                    GooglePlaceDetailsResponseDTO.class);

            if (detalles == null) {
                log.warn("Google Places API no devolvió detalles para placeId={}", placeId);
                throw new NoSuchElementException("Hospital inexistente");
            }

            return mapearAHospital(detalles);

        } catch (Exception e) {
            log.error("Error al obtener detalles del hospital con placeId={}: {}", placeId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Verifica si un {@link String} transporte del sistema es válido
     */
    public boolean esTransporteValido(String transporte) {
        return TRANSPORTES_PERMITIDOS_MAP.containsKey(transporte.toLowerCase());
    }

    /**
     * Calcula el tiempo estimado de arribo a un hospital
     */
    @Transactional
    public List<TiempoEstimadoArriboHospitalResponse> calcularTiempoArriboHospital(
            Hospital hospital, String transporte, Double latitud, Double longitud) {
        try {
            GooglePlacesComputeRouteResponseDTO response =
                    consultarRutasComputeRoutes(hospital, transporte, latitud, longitud);

            if (response == null || response.getRoutes().isEmpty()) {
                log.warn("La API de Google no devolvió resultados para lat={}, lng={}", latitud, longitud);
                throw new IllegalArgumentException("La API de Google no devolvió resultados, intente más tarde");
            }

            return this.mappearATiempoEstimadoArriboHospitalResponse(response, transporte, hospital.getId());

        } catch (Exception e) {
            log.error("Error al buscar hospitales cercanos en Google Places API: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Error en la API de Google, intente más tarde ");
        }
    }

    /**
     * Calcula el tiempo estimado de arribo de la mejor ruta (la etiquetada
     * como {@code DEFAULT_ROUTE}) para un hospital.
     *
     * Devuelve {@code null} si la API falla, no devuelve rutas o ninguna ruta
     * es la preferida, para no romper la lista de hospitales cercanos.
     *
     * @return el tiempo estimado de la ruta preferida o {@code null} si no se
     *         puede calcular.
     */
    public LocalTime calcularTiempoEstimadoArriboMejorRuta(
            Hospital hospital, String transporte, Double latitud, Double longitud) {
        try {
            GooglePlacesComputeRouteResponseDTO response =
                    consultarRutasComputeRoutes(hospital, transporte, latitud, longitud);

            RouteDTO rutaPreferida = obtenerRutaPreferida(response);
            if (rutaPreferida == null) {
                return null;
            }

            return this.convertDurationToTime(rutaPreferida.getDuration());
        } catch (Exception e) {
            log.warn("No se pudo calcular la mejor ruta de arribo para el hospital {}: {}", hospital.getId(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Construye el body del POST a Compute Routes para un hospital.
     */
    private Map<String, Object> construirBodyComputeRoutes(
            Hospital hospital, String transporte, Double latitud, Double longitud) {
        Direccion direccion = hospital.getDireccion();
        Map<String, Object> destinationMapRequest;

        if (direccion != null && direccion.getCoordenada() != null) {
            Coordenada coordenadaHospital = direccion.getCoordenada();

            destinationMapRequest = Map.of(
                    "location", Map.of(
                            "latLng", Map.of(
                                    "latitude", coordenadaHospital.getLatitud(),
                                    "longitude", coordenadaHospital.getLongitud())));
        } else {
            destinationMapRequest = Map.of(
                    "placeId", hospital.getPlaceId());
        }

        return Map.of(
                "origin", Map.of(
                        "location",
                        Map.of(
                                "latLng",
                                Map.of("latitude", latitud, "longitude", longitud))),
                "destination", destinationMapRequest,
                "travelMode", this.traducirTransportePermitido(transporte), // necesario dado que la api está en inglés
                "units", "METRIC", // se lo pido en metros
                "computeAlternativeRoutes", true // máximo de 3 rutas
        );
    }

    /**
     * Llama a la API Compute Routes y devuelve el response parseado.
     *
     * Las excepciones HTTP o de parseo se propagan al llamador, que decide
     * cómo tratarlas.
     */
    private GooglePlacesComputeRouteResponseDTO consultarRutasComputeRoutes(
            Hospital hospital, String transporte, Double latitud, Double longitud) {
        String responseJson = restClient.post()
                .uri(ROUTES_BASE_URL)
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                // El field mask va en header para controlar qué datos devuelve la API
                // y así no disparar SKUs más caros innecesariamente.
                .header("X-Goog-FieldMask", COMPUTE_ROUTES_FIELD_MASK)
                .body(construirBodyComputeRoutes(hospital, transporte, latitud, longitud))
                .retrieve()
                .body(String.class);

        return objectMapper.readValue(responseJson, GooglePlacesComputeRouteResponseDTO.class);
    }

    /**
     * Busca la ruta etiquetada como {@code RouteLabel.DEFAULT_ROUTE}, que es la
     * ruta "óptima" que Google propone por defecto.
     *
     * @return la ruta preferida o {@code null} si no existe.
     */
    private RouteDTO obtenerRutaPreferida(GooglePlacesComputeRouteResponseDTO response) {
        if (response == null || response.getRoutes() == null) {
            return null;
        }
        return response.getRoutes().stream()
                .filter(ruta -> ruta.getRouteLabels() != null
                        && ruta.getRouteLabels().contains(RouteLabel.DEFAULT_ROUTE))
                .findFirst()
                .orElse(null);
    }

    /**
     * Transforma un {@link String} transporte del sistema a un {@link String}
     * transporte de la API de Google Maps
     */
    private TravelMode traducirTransportePermitido(String transporteEnEspaniol) {
        if (!TRANSPORTES_PERMITIDOS_MAP.containsKey(transporteEnEspaniol.toLowerCase())) {
            throw new IllegalArgumentException("Transporte no permitido: " + transporteEnEspaniol);
        }

        return TRANSPORTES_PERMITIDOS_MAP.get(transporteEnEspaniol.toLowerCase());
    }

    /**
     * Transforma un {@link TravelMode} de la Api de google maps a
     * un modo transporte del sistema {@link String}
     */
    private String pasarAEspaniolTransporte(TravelMode transporteApiGoogleMaps) {
        for (Map.Entry<String, TravelMode> entry : TRANSPORTES_PERMITIDOS_MAP.entrySet()) {
            if (entry.getValue().compareTo(transporteApiGoogleMaps) == 0) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException(
                "No se encontró un modo de transporte en español para el modo de transporte de la API de Google Maps: "
                        + transporteApiGoogleMaps);
    }

    /**
     * Convierte un {@link GooglePlaceDetailsResponseDTO} a la entidad
     * {@link Hospital}.
     */
    private Hospital mapearAHospital(GooglePlaceDetailsResponseDTO detalles) {
        Hospital hospital = new Hospital();

        hospital.setPlaceId(detalles.getId());
        hospital.setNombre(
                detalles.getDisplayName() != null
                        ? detalles.getDisplayName().getText()
                        : "Nombre no disponible");

        Direccion direccion = direccionService.buscarOCrearDireccion(detalles.getAddressComponents(),
                detalles.getLocation(), detalles.getId());

        hospital.setDireccion(direccion);

        // Extraer país y ciudad de los addressComponents si están disponibles
        if (detalles.getAddressComponents() != null) {
            for (AddressComponent comp : detalles.getAddressComponents()) {
                if (comp.getTypes() == null)
                    continue;
            }
        }

        return hospital;
    }

    /**
     * Convierte un {@link GooglePlacesComputeRouteResponseDTO} a un
     * {@link TiempoEstimadoArriboHospitalResponse}.
     */
    private List<TiempoEstimadoArriboHospitalResponse> mappearATiempoEstimadoArriboHospitalResponse(
            GooglePlacesComputeRouteResponseDTO response, String transporte, Long idHospital) {
        // busco la ruta mas optima que me devolvio la api
        List<RouteDTO> rutas = response.getRoutes();

        if (rutas.isEmpty()) {
            throw new IllegalArgumentException("La API de Google no encontró una ruta válidá para el hospital");
        }

        List<TiempoEstimadoArriboHospitalResponse> rutasDTO = new ArrayList<>();

        rutas.forEach(ruta -> {
            TiempoEstimadoArriboHospitalResponse dto = new TiempoEstimadoArriboHospitalResponse();
            dto.setIdHospital(idHospital);
            dto.setTransporte(transporte);// transporte en español

            dto.setTiempoEstimadoArribo(
                    this.convertDurationToTime(ruta.getDuration()));

            dto.setDistanciaMetros(ruta.getDistanceMeters());
            dto.setPolylineCode(ruta.getPolyline().getEncodedPolyline());

            List<RouteLegDTO> tramosRuta = ruta.getLegs();

            if (!tramosRuta.isEmpty()) { // obtengo los pasos que se usan para estimar la ruta
                List<CombinacionRutasDTO> combinaciones = new ArrayList<>();
                tramosRuta.forEach(
                        leg -> leg.getSteps()
                                .forEach(step -> {
                                    TravelMode travelMode = step.getTravelMode();
                                    CombinacionRutasDTO combinacionRutasDTO = new CombinacionRutasDTO();

                                    if (travelMode == TravelMode.TRANSIT) {
                                        String lineaTransportePublico = step.getTransitDetails().getTransitLine()
                                                .getName();
                                        combinacionRutasDTO.setNombreLinea(lineaTransportePublico);
                                    }

                                    combinacionRutasDTO.setTipoTransporte(
                                            this.pasarAEspaniolTransporte(step.getTravelMode()));
                                    combinacionRutasDTO.setIndicaciones(Optional
                                            .ofNullable(step.getNavigationInstruction())
                                            .map(NavigationInstructionDTO::getInstructions)
                                            .orElse(""));
                                    combinaciones.add(combinacionRutasDTO);
                                }));

                dto.setCombinacionesLineas(combinaciones);
            }

            rutasDTO.add(dto);
        });

        return rutasDTO;
    }

    /**
     * Convierte un {@link String} duration a un {@link LocalTime}
     * 
     * @param duration es el tiempo de viaje en segundos, por ejemplo "300s"
     */
    private LocalTime convertDurationToTime(String duration) {
        duration = duration.replace("s", "");
        int durationInt = Integer.parseInt(duration);
        return LocalTime.ofSecondOfDay(durationInt);
    }
}
