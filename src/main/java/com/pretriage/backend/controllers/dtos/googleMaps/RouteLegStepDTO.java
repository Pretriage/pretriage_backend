package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RouteLegStepDTO {

    private String staticDuration;

    private Integer distanceMeters;

    private PolylineDTO polyline;

    private RouteLegStepTransitDetailsDTO transitDetails;// puede ser null

    private NavigationInstructionDTO navigationInstruction;

    private TravelMode travelMode;
}
