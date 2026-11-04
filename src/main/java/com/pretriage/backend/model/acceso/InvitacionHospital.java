package com.pretriage.backend.model.acceso;

import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.model.personas.TipoMatriculaProfesional;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Entity
public class InvitacionHospital {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Hospital hospital;

    @Column(nullable = false)
    private String emailNormalizado;

    @Enumerated(EnumType.STRING)
    private EstadoInvitacionHospital estado = EstadoInvitacionHospital.PENDIENTE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "invitacion_hospital_rol", joinColumns = @JoinColumn(name = "invitacion_id"))
    @Column(name = "rol")
    @Enumerated(EnumType.STRING)
    private Set<RolMembresiaHospital> rolesSolicitados = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "invitacion_hospital_especialidad", joinColumns = @JoinColumn(name = "invitacion_id"))
    @Column(name = "especialidad_id")
    private Set<Long> especialidadIds = new LinkedHashSet<>();

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    private String matricula;
    @Enumerated(EnumType.STRING)
    private TipoMatriculaProfesional tipoMatricula;
    private String jurisdiccionMatricula;
    private Instant venceEn;
    private Instant fechaCreacion = Instant.now();
    private Instant fechaAceptacion;
    private Boolean emailEnviado = false;
    private Instant ultimoIntentoEnvio;
    private Integer cantidadIntentosEnvio = 0;

    @ManyToOne(optional = false)
    private UsuarioAuth invitadaPor;

    @ManyToOne
    private UsuarioAuth aceptadaPor;
}
