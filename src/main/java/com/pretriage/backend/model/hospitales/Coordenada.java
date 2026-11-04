package com.pretriage.backend.model.hospitales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter 
@Setter
@Entity
public class Coordenada {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Double latitud;
    private Double longitud;
}
