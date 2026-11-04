package com.pretriage.backend.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pretriage.backend.model.hospitales.Direccion;

@ExtendWith(MockitoExtension.class)
public class DireccionTest {

    
    private Direccion direccion;

    @BeforeEach 
    void setUP(){
        this.direccion = new Direccion();
        this.direccion.setCalle("someStreet");
        this.direccion.setAltura("123");
        this.direccion.setCodigoPostal("C123WB");
        this.direccion.setCiudad("CABA");
        this.direccion.setProvincia("Buenos Aires");
    }

    @Test 
    void sePuedeFormatearLaDireccionAString(){
        
        String result = this.direccion.formateada();

        String resultExpectedWithoutPiso =  this.direccion.getCalle() + " " + this.direccion.getAltura()
        + ", "+ this.direccion.getCodigoPostal() + ", " +
        this.direccion.getCiudad() + ", " + this.direccion.getProvincia();
        assertEquals(resultExpectedWithoutPiso , result);
    }
    
}
