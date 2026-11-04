package com.pretriage.backend.model.acceso;

import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.UsuarioAuth;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
public class AuditoriaHospital {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    private Hospital hospital;
    @ManyToOne(optional = false)
    private UsuarioAuth actor;
    private Instant fecha = Instant.now();
    private String accion;
    private String objetivo;
    @Column(length = 1000)
    private String resultado;
}
