package com.pretriage.backend.repositories;

import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.MembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.Recepcionista;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.model.recepcion.AdmisionRecepcion;
import com.pretriage.backend.model.recepcion.SesionRecepcion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepoAccesoHistorialHospitalTest {
    @Autowired private TestEntityManager em;
    @Autowired private RepoAtencionesMedicas atenciones;
    @Autowired private RepoAdmisionesRecepcion admisiones;

    @Test
    void restringeHistorialSegunLaMembresiaDelHospitalDeLaSesion() {
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|historial-hospital");
        em.persist(usuario);
        Hospital hospital = hospital("historial-hospital");
        em.persist(membresiaSuspendida(usuario, hospital("historial-otro-hospital")));

        Medico medico = new Medico();
        medico.setUsuarioAuth(usuario);
        em.persist(medico);
        SesionAtencionMedica sesionMedica = new SesionAtencionMedica();
        sesionMedica.setMedico(medico);
        sesionMedica.setHospital(hospital);
        em.persist(sesionMedica);

        Recepcionista recepcionista = new Recepcionista();
        recepcionista.setUsuarioAuth(usuario);
        em.persist(recepcionista);
        SesionRecepcion sesionRecepcion = new SesionRecepcion();
        sesionRecepcion.setRecepcionista(recepcionista);
        sesionRecepcion.setHospital(hospital);
        em.persist(sesionRecepcion);

        Paciente paciente = em.persist(new Paciente());
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setPaciente(paciente);
        consulta.setHospital(hospital);
        em.persist(consulta);

        AtencionMedica atencion = new AtencionMedica();
        atencion.setConsultaMedica(consulta);
        atencion.setSesionAtencionMedica(sesionMedica);
        em.persist(atencion);
        AdmisionRecepcion admision = new AdmisionRecepcion();
        admision.setConsultaMedica(consulta);
        admision.setSesionRecepcion(sesionRecepcion);
        em.persist(admision);

        verificarAcceso(usuario, medico, paciente, admision, true, true);
        MembresiaHospital membresia = em.persist(membresiaSuspendida(usuario, hospital));
        verificarAcceso(usuario, medico, paciente, admision, false, false);
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.getRoles().add(RolMembresiaHospital.RECEPCIONISTA);
        verificarAcceso(usuario, medico, paciente, admision, false, true);
        membresia.getRoles().add(RolMembresiaHospital.MEDICO);
        verificarAcceso(usuario, medico, paciente, admision, true, true);
        membresia.getRolesSuspendidos().add(RolMembresiaHospital.MEDICO);
        verificarAcceso(usuario, medico, paciente, admision, false, true);
        membresia.getRolesSuspendidos().remove(RolMembresiaHospital.MEDICO);
        membresia.getRolesSuspendidos().add(RolMembresiaHospital.RECEPCIONISTA);
        verificarAcceso(usuario, medico, paciente, admision, true, false);
    }

    private void verificarAcceso(UsuarioAuth usuario, Medico medico, Paciente paciente,
            AdmisionRecepcion admision, boolean accesoMedico, boolean accesoRecepcion) {
        em.flush();
        assertEquals(accesoMedico ? 1 : 0,
                atenciones.findBySesionAtencionMedicaMedicoUsuarioAuthIdOrderByFechaHoraInicioDesc(usuario.getId()).size());
        assertEquals(accesoMedico,
                atenciones.existsByConsultaMedicaPacienteIdAndSesionAtencionMedicaMedicoId(paciente.getId(), medico.getId()));
        assertEquals(accesoRecepcion,
                admisiones.findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(admision.getId(), usuario.getId()).isPresent());
    }

    private Hospital hospital(String nombre) {
        Hospital hospital = new Hospital();
        hospital.setPlaceId(nombre);
        hospital.setNombre(nombre);
        return em.persist(hospital);
    }

    private MembresiaHospital membresiaSuspendida(UsuarioAuth usuario, Hospital hospital) {
        MembresiaHospital membresia = new MembresiaHospital();
        membresia.setUsuario(usuario);
        membresia.setHospital(hospital);
        membresia.setEstado(EstadoMembresiaHospital.SUSPENDIDA);
        return membresia;
    }
}
