package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Coordenada;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoCoordenadas extends JpaRepository<Coordenada, Long> {

    Optional<Coordenada> findByLatitudAndLongitud(Double latitud, Double longitud);
}
