package com.pretriage.backend.model.acceso;

import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.UsuarioAuth;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_auth_id", "hospital_id"}))
public class MembresiaHospital {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_auth_id")
    private UsuarioAuth usuario;

    @ManyToOne(optional = false)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    private EstadoMembresiaHospital estado = EstadoMembresiaHospital.INVITADA;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membresia_hospital_rol", joinColumns = @JoinColumn(name = "membresia_id"))
    @Column(name = "rol")
    @Enumerated(EnumType.STRING)
    private Set<RolMembresiaHospital> roles = new LinkedHashSet<>();

    private Instant fechaCreacion = Instant.now();
    private Instant fechaAceptacion;
    private Instant fechaSuspension;

    @ManyToOne
    @JoinColumn(name = "creada_por_usuario_auth_id")
    private UsuarioAuth creadaPor;
}
