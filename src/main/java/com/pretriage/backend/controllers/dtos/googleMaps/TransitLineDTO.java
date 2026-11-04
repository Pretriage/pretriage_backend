package com.pretriage.backend.controllers.dtos.googleMaps;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransitLineDTO {

    private String name; // ejemplo: "Plaza Once (98 - 3n, 3v) - Calle 153, 2457"

    private String nameShort; // ejemplo: "98"
}
