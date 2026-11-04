package com.pretriage.backend.repositories;

import com.pretriage.backend.model.hospitales.Direccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepoDirecciones extends JpaRepository<Direccion, Long> {

    Optional<Direccion> findByCalleAndAltura(String calle, String altura);
}
