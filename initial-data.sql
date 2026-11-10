INSERT INTO coordenada (latitud, longitud)
VALUES (-34.5970161, -58.4217347),
(-34.597568599999995, -58.422380399999994),
(-34.597048099999995, -58.4218436),
(-34.595669, -58.420084800000005),
(-34.600502, -58.4243975),
(-34.5983236, -58.4145931);
                                
INSERT INTO direccion (calle, altura, ciudad, provincia, codigo_postal, id_coordenada)
VALUES ('Av. Córdoba', '3922','Cdad. Autónoma de Buenos Aires', 'Buenos Aires', 'C1188AAF', 1),
('Av. Córdoba', '3933','Cdad. Autónoma de Buenos Aires', 'Buenos Aires', 'C1188AAF', 2),
('Francisco Acuña de Figueroa','1240', 'Cdad. Autónoma de Buenos Aires', 'Buenos Aires', 'C1180 AAX', 3),
('Av. Medrano', '1174', 'Cdad. Autónoma de Buenos Aires', 'Buenos Aires', 'C1179AAV', 4),
('Guardia Vieja', '4246', 'Cdad. Autónoma de Buenos Aires','Buenos Aires', 'C1192AAT', 5),
('Billinghurst', '979', 'Cdad. Autónoma de Buenos Aires','Buenos Aires', 'C1174ABQ', 6);

INSERT INTO hospital (nombre, place_id, id_direccion)
VALUES ('Güemes Hospital', 'ChIJs-z8Lo_KvJURmzvvmNOCPBs', 1),
('Sanatorio Güemes', 'ChIJNRfDkHzKvJURmIEYjCVmkaA', 2),
('Hospital Let', 'ChIJ3zSGiHzKvJURl_HqbC-3GJQ', 3),
('Consultorio Medrano', 'ChIJ02BcmkLLvJURJ2W9EmKZawY',4),
('Centro Médico \"Ramón Cardillo\"', 'ChIJqeGSt2TKvJURFncumHEoNHk', 5),
('Centro Médico ALASBA', 'ChIJq-lKLtLKvJUR9zlh-P8L_AM',6);

/*asumo que ya están cargadas las especialidades médicas:
1 == "CLINICA_MEDICA", "Clínica médica"
2 == "PEDIATRIA", "Pediatría"
3 == "CARDIOLOGIA", "Cardiología"
4 == "TRAUMATOLOGIA", "Traumatología"
5 == "GINECOLOGIA", "Ginecología"*/
INSERT INTO  hospital_especialidad_medica (id_especialidad_medica, id_hospital)
VALUES (1,1),(4,1),
(1,2),(4,2),
(1,3),(4,3),(3,3),(2,3),(5,3),
(1,4),(2,4),
(1,5),
(1,6),(2,6),(3,6),(4,6),(5,6);

INSERT INTO obra_social (nombre, virgente)
 VALUES ('OSDE', true), ('IOMA', true);

/*Usuario admin de plataforma*/
INSERT INTO usuario_auth (id, nombre, apellido, numero_documento, tipo_documento, correo_electronico, rol)
VALUES ('auth0|6ae8f2d21bf2387348c4fb81', 'Francisco', 'Castillo', '43244598', 'DNI','fcallero@frba.utn.edu.ar', 0);


INSERT INTO sector (nombre, activa, id_hospital, id_especialidad) 
values ('Urgencias Clinica', true, 1, 1),
('Urgencias Traumatologia', true, 1, 4),
('Urgencias Clinica', true, 2, 1),
('Urgencias Traumatologia', true, 2, 4),
('Urgencias Clinica', true, 3, 1),
('Urgencias Pediatria', true, 3, 2),
('Urgencias Cardiologia', true, 3, 3),
('Urgencias Traumatologia', true, 3, 4),
('Urgencias Ginecologia', true, 3, 5),
('Urgencias Clinica', true, 4, 1),
('Urgencias Pediatria', true, 4, 2),
('Urgencias Clinica', true, 5, 1),
('Urgencias Clinica', true, 6, 1),
('Urgencias Pediatria', true, 6, 2),
('Urgencias Cardiologia', true, 6, 3),
('Urgencias Traumatologia', true, 6, 4),
('Urgencias Ginecologia', true, 6, 5);


INSERT INTO sala (nombre, id_sector, activa, id_hospital, id_especialidad_medica)
values ('S1', 1, true, 1, 1),
('S1', 2, true, 1, 4),
('S1', 3, true, 2, 1),
('S1', 4, true, 2, 4),
('S1', 5, true, 3, 1),
('S1', 6, true, 3, 2),
('S1', 7, true, 3, 3),
('S1', 8, true, 3, 4),
('S1', 9, true, 3, 5),
('S1', 10, true, 4, 1),
('S1', 11, true, 4, 2),
('S1', 12, true, 5, 1),
('S1', 13, true, 6, 1),
('S1', 14, true, 6, 2),
('S1', 15, true, 6, 3),
('S1', 16, true, 6, 4),
('S1', 17, true, 6, 5);

INSERT INTO gestor_de_cola (id_hospital, id_especialidad_medica, id_sector)
values (1, 1, 1), (1, 4, 2),
(2, 1, 3), (2, 4, 4),
(3, 1, 5), (3, 2, 6),(3, 3, 7), (3, 4, 8),(3, 5, 9),
(4, 1, 10), (4, 2, 11),
(5, 1, 12),
(6, 1, 13), (6, 2, 14),(6, 3, 15), (6, 4, 16),(6, 5, 17);
