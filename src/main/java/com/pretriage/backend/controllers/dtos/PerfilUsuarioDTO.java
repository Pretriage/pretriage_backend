package com.pretriage.backend.controllers.dtos;

import java.time.LocalDate;

import com.pretriage.backend.model.personas.Genero;
import com.pretriage.backend.model.personas.TipoDocumento;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PerfilUsuarioDTO {
    @NotBlank(message = "El nombre no puede estar vacío")
    private String nombre;

    @NotBlank(message = "El apellido no puede estar vacío")
    private String apellido;
    @NotNull(message = "El tipo de documento no puede ser nulo")
    private TipoDocumento tipoDocumento;
    @NotBlank(message = "El número de documento no puede estar vacío")
    private String numeroDocumento;

    @Past(message = "La fecha de nacimiento debe ser una fecha pasada")
    private LocalDate fechaNacimiento;

    @NotNull(message = "El género biológico no puede ser nulo")
    private Genero generoBiologico;

    @NotNull(message = "El género biológico no puede ser nulo")
    private Genero generoConElQueSeIdentifica;

    @Email(message = "El correo electrónico debe ser válido")
    private String email;

    @NotBlank(message = "El teléfono no puede estar vacío")
    private String telefono;

    @NotBlank(message = "La calle no puede estar vacía")
    private String calle;

    @NotBlank(message = "La altura no puede estar vacía")
    private String alturaDireccion;

    private String piso;

    @NotBlank(message = "El código postal no puede estar vacío")
    private String codigoPostal;
    @NotBlank(message = "La ciudad no puede estar vacía")
    private String ciudad;

    @NotBlank(message = "La provincia no puede estar vacía")
    private String provincia;

    @NotNull(message = "El peso no puede ser nulo")
    private Double peso;
    @NotNull(message = "La altura no puede ser nula")
    @Min(value = 1, message = "La altura debe ser mayor a 0")
    private Integer alturaPersona;
}