package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.googleMaps.AddressComponent;
import com.pretriage.backend.controllers.dtos.googleMaps.LatLng;
import com.pretriage.backend.model.hospitales.Coordenada;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.repositories.RepoCoordenadas;
import com.pretriage.backend.repositories.RepoDirecciones;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DireccionService {

    private final RepoDirecciones repoDirecciones;
    private final RepoCoordenadas repoCoordenadas;

    private static final Logger log = LoggerFactory.getLogger(DireccionService.class);

    public Direccion buscarOCrearDireccion(List<AddressComponent> addressComponents, LatLng location, String placeId) {
        HashMap<String, String> direccionComponents = new HashMap<>();

        if (addressComponents == null || addressComponents.isEmpty()) {
            log.warn("No hay addressComponents para placeId={}. Solo se conserva formattedAddress.",
                    placeId);
            return null;
        }

        for (AddressComponent componente : addressComponents){
            if (componente.getTypes() == null) continue;

            // longText devuelve el nombre completo; shortText devuelve abreviatura.
            // Para calle, ciudad, país, etc. usamos longText.
            // Para provincia podría usarse shortText (ej: "BA" en vez de "Buenos Aires").
            String valor = componente.getLongText();
            if (valor == null || valor.isBlank()) continue;

            for (String tipo : componente.getTypes()) {
                switch (tipo) {
                    case "route" -> direccionComponents.put("calle", valor);

                    case "street_number" -> direccionComponents.put("altura", valor);

                    case "floor" -> direccionComponents.put("piso", valor);

                    case "postal_code" -> direccionComponents.put("codigoPostal", valor);

                    // Tipos ignorados intencionalmente:
                    // "political", "administrative_area_level_2", "natural_feature", etc.
                    default -> {
                    } // no hacer nada
                }
            }
        }

        Optional<Direccion> opDireccion = repoDirecciones.findByCalleAndAltura(direccionComponents.get("calle"), direccionComponents.get("altura"));

        Direccion direccion = opDireccion.orElseGet(Direccion::new);

        if (location!= null) {
            Optional<Coordenada> optionalCoordenada = repoCoordenadas.findByLatitudAndLongitud(location.getLatitude(), location.getLongitude());

            Coordenada coordenada = optionalCoordenada.orElseGet(Coordenada::new);
            coordenada.setLatitud(location.getLatitude());
            coordenada.setLongitud(location.getLongitude());
            repoCoordenadas.save(coordenada);
            direccion.setCoordenada(coordenada);
        }

        return direccion;
    }
}
