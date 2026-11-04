package com.pretriage.backend.model.personas;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class UsuarioAuth {
    @Id
    private String id; //auth0 id

    private String nombre;
    private String apellido;
    private String numeroDocumento;

    @Enumerated(EnumType.STRING)
    private TipoDocumento tipoDocumento;

    private String correoElectronico;

    private RolSistema rol;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CambioContraseniaToken> cambiosDeContrasenia = new ArrayList<>();
}
