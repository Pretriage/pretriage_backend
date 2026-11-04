package com.pretriage.backend.controllers.dtos;

import java.util.List;

public record RecepcionHospitalDTO(Long id, String nombre, List<EspecialidadMedicaDTO> especialidades) {}
