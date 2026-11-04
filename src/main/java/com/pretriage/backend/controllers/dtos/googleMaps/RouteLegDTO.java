package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RouteLegDTO {

    private List<RouteLegStepDTO> steps;

    private String duration;

    private Integer distanceMeters;

    private PolylineDTO polyline;

}
