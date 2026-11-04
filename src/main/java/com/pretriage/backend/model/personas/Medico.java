package com.pretriage.backend.model.personas;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Medico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "auth_id", referencedColumnName = "id")
    private UsuarioAuth usuarioAuth;

    private String matricula;

    @OneToMany(mappedBy = "medico", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CredencialProfesional> credencialesProfesionales;

    @OneToMany(mappedBy = "medico")
    private List<AsignacionMedicoHospital> asignaciones;

    public Medico() {
        this.asignaciones = new ArrayList<>();
        this.credencialesProfesionales = new ArrayList<>();
    }
}
