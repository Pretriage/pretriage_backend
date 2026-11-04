package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.personas.Genero;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import java.time.LocalDate;

public record PacienteRecepcionDTO(Long id, String dni, String nombre, String apellido,
                                   LocalDate fechaNacimiento, Genero generoBiologico,
                                   String telefono, String correoElectronico,
                                   String calle, String alturaDomicilio, String piso,
                                   String ciudad, String provincia, String codigoPostal,
                                   boolean registradoEnAplicacion, boolean atencionEnCurso,
                                   EstadoConsulta estadoAtencionEnCurso) {}
