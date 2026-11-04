package com.pretriage.backend.controllers.dtos.googleMaps;

public enum RouteLabel {
    ROUTE_LABEL_UNSPECIFIED,	//Predeterminado: No se usa
    DEFAULT_ROUTE,//Es la ruta "óptima" predeterminada que se muestra para el cálculo de la ruta.
    DEFAULT_ROUTE_ALTERNATE, //	Es una alternativa a la ruta "óptima" predeterminada. Las rutas como esta se mostrarán cuando se especifique computeAlternativeRoutes.
    FUEL_EFFICIENT, //Ruta con mayor ahorro de combustible. Se determina que las rutas etiquetadas con este valor están optimizadas para parámetros ecológicos, como el consumo de combustible.
    SHORTER_DISTANCE //Es la ruta con la distancia de viaje más corta. Esta función se encuentra en fase experimental.
}
