package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GooglePlacesComputeRouteResponseDTO {

    private List<RouteDTO> routes;

}
