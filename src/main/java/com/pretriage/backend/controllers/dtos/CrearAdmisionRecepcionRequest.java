package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.personas.Genero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record CrearAdmisionRecepcionRequest(
        @NotNull Long sesionId,
        @NotBlank @Pattern(regexp = "\\d{7,8}") String dni,
        @NotBlank String nombre,
        @NotBlank String apellido,
        @NotNull @Past LocalDate fechaNacimiento,
        @NotNull Genero generoBiologico,
        @NotBlank @Pattern(regexp = "^[+0-9 ()-]{6,25}$") String telefono,
        @Email String correoElectronico,
        @NotBlank String calle,
        @NotBlank String alturaDomicilio,
        String piso,
        @NotBlank String ciudad,
        @NotBlank String provincia,
        String codigoPostal,
        @NotBlank String codigoEspecialidad) {}
