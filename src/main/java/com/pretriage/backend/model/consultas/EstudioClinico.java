package com.pretriage.backend.model.consultas;

import java.time.LocalDateTime;

import com.pretriage.backend.model.personas.Paciente;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class EstudioClinico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "id_paciente", referencedColumnName = "id")
    private Paciente paciente;

    private String nombreArchivo;
    private String tipoArchivo;
    private String extensionArchivo;
    private String descripcion;

    private LocalDateTime fechaSubida;
    private Long tamanoArchivo;

    private String rutaArchivo;

    private boolean activo;

    public EstudioClinico() {
        this.fechaSubida = LocalDateTime.now();
        this.activo=true;
    }
}
