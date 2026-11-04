package com.pretriage.backend.mappers;

import com.pretriage.backend.controllers.dtos.CredencialResponse;
import com.pretriage.backend.model.hospitales.Credencial;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MapperCredencial {

    public static CredencialResponse toDTOResponse(Credencial credencial) {
        return new CredencialResponse(
                credencial.getId(),
                credencial.getNumeroAfiliado(),
                credencial.getPlan(),
                credencial.getFechaVencimiento(),
                credencial.getObraSocial().getNombre());
    }
}
