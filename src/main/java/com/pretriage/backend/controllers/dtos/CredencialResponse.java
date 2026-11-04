package com.pretriage.backend.controllers.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
public class CredencialResponse {

    private Long id;

    private String numeroAfiliado;

    private String plan;

    private LocalDate fechaVencimiento;

    private String nombreObraSocial;
}