package com.pretriage.backend.model.hospitales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter 
@Setter
@Entity
public class Direccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String calle;

    private String altura;

    private String piso;

    private String codigoPostal;

    private String ciudad;

    private String provincia;

    @OneToOne
    @JoinColumn(name="id_coordenada", referencedColumnName = "id")
    private Coordenada coordenada;

    /**
     * Formatea la dirección como "calle altura, piso, codigoPostal, ciudad, provincia",
     * omitiendo componentes nulos o en blanco. Retorna {@code null} si la dirección es vacía.
     */
    public String formateada() {
        String calleYAltura = Stream.of(calle, altura)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(" "));
        String resultado = Stream.concat(
                Stream.of(calleYAltura),
                Stream.of(piso, codigoPostal, ciudad, provincia))
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(", "));
        return resultado.isBlank() ? null : resultado;
    }
}
