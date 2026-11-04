package com.pretriage.backend.controllers.dtos.googleMaps;

public enum TravelMode {
    TRAVEL_MODE_UNSPECIFIED, // No se especificó ningún medio de transporte. La configuración predeterminada
                             // es DRIVE
    DRIVE, // Viaja en automóvil de pasajeros.
    BICYCLE, // Viaja en bicicleta.
    WALK, // Viaja a pie.
    TWO_WHEELER, // Vehículo motorizado de dos ruedas. Por ejemplo, una motocicleta.
    // Ten en cuenta que esto difiere del modo de viaje BICYCLE, que abarca el modo
    // en que funcionan las personas.
    TRANSIT,// Viaja por rutas de transporte público, si están disponibles.
}
