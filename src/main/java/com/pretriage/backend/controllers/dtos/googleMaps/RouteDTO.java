package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RouteDTO {

    private String duration;
    private Integer distanceMeters;
    private PolylineDTO polyline;
    private List<RouteLabel> routeLabels;
    private List<RouteLegDTO> legs;

}
