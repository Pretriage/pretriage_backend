package com.pretriage.backend.model;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.GestorDeCola;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.hospitales.Hospital;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class GestorDeColaTest {


    private Hospital hospitalMock;
    private GestorDeCola gestorDeCola;

    @BeforeEach
    void setUp(){
        this.gestorDeCola = new GestorDeCola();
        this.hospitalMock = new Hospital();
        this.gestorDeCola.setHospital(this.hospitalMock);
    }

    @Test
    void sePuedeIngresarUnaConsultaMedicaEnLaCola(){

        ConsultaMedica consultaMedicaMock = new ConsultaMedica();
        consultaMedicaMock.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaMock.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        consultaMedicaMock.setHospital(this.hospitalMock);
        consultaMedicaMock.setEstadoConsulta(EstadoConsulta.PENDIENTE);


        this.gestorDeCola.agregarConsultaMedicaALaCola(consultaMedicaMock);

        assertEquals(1,this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(consultaMedicaMock, this.gestorDeCola.getConsultasEnEspera().getFirst());
    }

    @Test
    void alIngresarOtraConsultaMedicaSeReordenaLaColaPorPrioridad(){

        ConsultaMedica consultaMedicaMock1 = new ConsultaMedica();
        consultaMedicaMock1.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaMock1.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        consultaMedicaMock1.setHospital(this.hospitalMock);
        consultaMedicaMock1.setEstadoConsulta(EstadoConsulta.PENDIENTE);


        this.gestorDeCola.agregarConsultaMedicaALaCola(consultaMedicaMock1);

        assertEquals(1,this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(consultaMedicaMock1, this.gestorDeCola.getConsultasEnEspera().getFirst());

        //llega otro paciente a la cola
        ConsultaMedica consultaMedicaMock2 = new ConsultaMedica();
        consultaMedicaMock2.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaMock2.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
        consultaMedicaMock2.setHospital(this.hospitalMock);
        consultaMedicaMock2.setEstadoConsulta(EstadoConsulta.PENDIENTE);


        this.gestorDeCola.agregarConsultaMedicaALaCola(consultaMedicaMock2);

        assertEquals(2,this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(consultaMedicaMock2, this.gestorDeCola.getConsultasEnEspera().getFirst());//prioriza al urgente
        assertEquals(consultaMedicaMock1, this.gestorDeCola.getConsultasEnEspera().getLast());
    }

    @Test
    void alIngresarOtraConsultaMedicaSeReordenaLaColaPorPrioridadYSeEliminanLasFinalizadas(){

        ConsultaMedica consultaMedicaMock1 = new ConsultaMedica();
        consultaMedicaMock1.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaMock1.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        consultaMedicaMock1.setHospital(this.hospitalMock);
        consultaMedicaMock1.setEstadoConsulta(EstadoConsulta.PENDIENTE);

        this.gestorDeCola.agregarConsultaMedicaALaCola(consultaMedicaMock1);

        assertEquals(1,this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(consultaMedicaMock1, this.gestorDeCola.getConsultasEnEspera().getFirst());

        consultaMedicaMock1.setEstadoConsulta(EstadoConsulta.FINALIZADA);

        //llega otro paciente a la cola
        ConsultaMedica consultaMedicaMock2 = new ConsultaMedica();
        consultaMedicaMock2.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaMock2.setNivelDeGravedadBot(NivelDeGravedad.URGENTE);
        consultaMedicaMock2.setHospital(this.hospitalMock);
        consultaMedicaMock2.setEstadoConsulta(EstadoConsulta.PENDIENTE);

        this.gestorDeCola.agregarConsultaMedicaALaCola(consultaMedicaMock2);

        assertEquals(1,this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(consultaMedicaMock2, this.gestorDeCola.getConsultasEnEspera().getFirst());
    }

    @Test
    void alIngresarVariosPacientesSeOrdenaLaColaPorPrioridadYHoraDeLlegada(){
        LocalDateTime base = LocalDateTime.of(2026, 6, 29, 10, 0);

        ConsultaMedica normalTemprano = consultaEnEspera(base.plusMinutes(1), NivelDeGravedad.NORMAL);
        ConsultaMedica riesgoVital = consultaEnEspera(base.plusMinutes(4), NivelDeGravedad.RIESGO_VITAL_INMEDIATO);
        ConsultaMedica urgenteTemprano = consultaEnEspera(base.plusMinutes(2), NivelDeGravedad.URGENTE);
        ConsultaMedica noUrgente = consultaEnEspera(base.plusMinutes(3), NivelDeGravedad.NO_URGENTE);
        ConsultaMedica urgenteTarde = consultaEnEspera(base.plusMinutes(5), NivelDeGravedad.URGENTE);

        this.gestorDeCola.agregarConsultaMedicaALaCola(normalTemprano);
        this.gestorDeCola.agregarConsultaMedicaALaCola(riesgoVital);
        this.gestorDeCola.agregarConsultaMedicaALaCola(urgenteTemprano);
        this.gestorDeCola.agregarConsultaMedicaALaCola(noUrgente);
        this.gestorDeCola.agregarConsultaMedicaALaCola(urgenteTarde);

        assertEquals(5, this.gestorDeCola.getConsultasEnEspera().size());
        assertEquals(List.of(riesgoVital, urgenteTemprano, urgenteTarde, normalTemprano, noUrgente),
                this.gestorDeCola.getConsultasEnEspera());
    }

    private ConsultaMedica consultaEnEspera(LocalDateTime fechaHoraCreacion, NivelDeGravedad nivelDeGravedad) {
        ConsultaMedica consultaMedica = new ConsultaMedica();
        consultaMedica.setFechaHoraCreacion(fechaHoraCreacion);
        consultaMedica.setNivelDeGravedadBot(nivelDeGravedad);
        consultaMedica.setHospital(this.hospitalMock);
        consultaMedica.setEstadoConsulta(EstadoConsulta.PENDIENTE);
        return consultaMedica;
    }

}



