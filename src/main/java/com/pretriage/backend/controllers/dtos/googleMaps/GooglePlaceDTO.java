package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GooglePlaceDTO {
    private String id;
    private LocalizedText displayName;
    private String formattedAddress;
    private LatLng location;
    private List<String> types;
    private Double rating;
    private Integer userRatingCount;

}
