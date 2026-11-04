package com.pretriage.backend.config;

import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Order(1)
public class EspecialidadesMedicasSeeder implements ApplicationRunner {

    private final RepoEspecialidadesMedicas repoEspecialidadesMedicas;

    @Override
    public void run(ApplicationArguments args) {
        List<EspecialidadInicial> especialidades = List.of(
                new EspecialidadInicial("CLINICA_MEDICA", "Clinica medica"),
                new EspecialidadInicial("PEDIATRIA", "Pediatria"),
                new EspecialidadInicial("CARDIOLOGIA", "Cardiologia"),
                new EspecialidadInicial("TRAUMATOLOGIA", "Traumatologia"),
                new EspecialidadInicial("GINECOLOGIA", "Ginecologia")
        );

        especialidades.stream()
                .filter(especialidad -> !repoEspecialidadesMedicas.existsByCodigo(especialidad.codigo()))
                .map(this::crearEspecialidad)
                .forEach(repoEspecialidadesMedicas::save);
    }

    private EspecialidadMedica crearEspecialidad(EspecialidadInicial especialidadInicial) {
        EspecialidadMedica especialidad = new EspecialidadMedica();
        especialidad.setCodigo(especialidadInicial.codigo());
        especialidad.setNombre(especialidadInicial.nombre());
        return especialidad;
    }

    private record EspecialidadInicial(String codigo, String nombre) {
    }
}
