package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.googleMaps.AddressComponent;
import com.pretriage.backend.controllers.dtos.googleMaps.LatLng;
import com.pretriage.backend.model.hospitales.Coordenada;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.repositories.RepoCoordenadas;
import com.pretriage.backend.repositories.RepoDirecciones;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DireccionServiceTest {

    @Mock
    private RepoDirecciones repoDirecciones;

    @Mock
    private RepoCoordenadas repoCoordenadas;

    @InjectMocks
    private DireccionService direccionService;

    @Test
    void seCreaUnaDireccionNuevaSiNoExiste() throws Exception {

        AddressComponent calle = new AddressComponent();
        calle.setLongText("Av. Corrientes");
        calle.setTypes(List.of("route"));

        AddressComponent altura = new AddressComponent();
        altura.setLongText("1234");
        altura.setTypes(List.of("street_number"));

        LatLng location = new LatLng();
        location.setLatitude(-34.6);
        location.setLongitude(-58.4);

        when(repoDirecciones.findByCalleAndAltura(
                "Av. Corrientes", "1234"))
                .thenReturn(Optional.empty());

        when(repoCoordenadas.findByLatitudAndLongitud(
                -34.6, -58.4))
                .thenReturn(Optional.empty());


        Direccion direccion = direccionService.buscarOCrearDireccion(
                        List.of(calle, altura),
                        location,
                        "placeId");

        assertNotNull(direccion);
        assertNotNull(direccion.getCoordenada());

        verify(repoCoordenadas).save(any(Coordenada.class));
    }

    @Test
    void seReutilizaUnaDireccionExistente() throws Exception {

        AddressComponent calle = new AddressComponent();
        calle.setLongText("Av. Corrientes");
        calle.setTypes(List.of("route"));

        AddressComponent altura = new AddressComponent();
        altura.setLongText("1234");
        altura.setTypes(List.of("street_number"));

        Direccion direccionExistente = new Direccion();

        when(repoDirecciones.findByCalleAndAltura(
                "Av. Corrientes", "1234"))
                .thenReturn(Optional.of(direccionExistente));


        Direccion direccion = direccionService.buscarOCrearDireccion(
                List.of(calle,altura),
                null,
                "placeId");

        assertSame(direccionExistente, direccion);
    }

    @Test
    void devuelveNullSiNoHayAddressComponents() throws Exception {
        Direccion direccion = direccionService.buscarOCrearDireccion(
                Collections.emptyList(),
                null,
                "placeId");


        assertNull(direccion);

        verifyNoInteractions(repoDirecciones);
        verifyNoInteractions(repoCoordenadas);
    }

}
