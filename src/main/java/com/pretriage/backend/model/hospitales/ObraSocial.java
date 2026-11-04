package com.pretriage.backend.model.hospitales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class ObraSocial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @OneToMany(mappedBy = "obraSocial")
    private List<Credencial> credenciales;

    private boolean virgente;

    public ObraSocial(){
        this.credenciales = new ArrayList<>();
        this.virgente = true;
    }
}
