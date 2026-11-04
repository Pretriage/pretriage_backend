# filtro-valoracion-hospitales Specification

## Purpose

Permite al paciente seleccionar hospitales considerando su valoración (puntaje promedio y cantidad de reseñas de Google Places, la API de lugares de Google Maps) además de distancia y tiempo de atención, exponiendo esos datos junto a cada hospital del listado cercano.

## Requirements

### Requirement: El listado cercano expone la valoración de cada hospital
El sistema SHALL devolver, junto a cada hospital del listado de `GET /api/hospitales/cercanos` (los hospitales resultantes de filtrar por especialidad y `disponible=true`), el campo `valoracionPromedio` y el campo `cantidadValoraciones`. `valoracionPromedio` MUST ser el puntaje promedio de Google Places en la escala que devuelve Google, un número flotante entre 1.0 y 5.0, y `cantidadValoraciones` MUST ser la cantidad de reseñas registradas. La valoración MUST provenir de Google Places; cuando Google no provea valoración para un hospital, el campo ausente MUST devolverse como `null` (ausencia de datos, no un error), de modo que el frontend lo distinga de un puntaje real.

#### Scenario: Hospital con valoración disponible
- **WHEN** el paciente solicita los hospitales cercanos y Google Places tiene puntaje y cantidad de reseñas para un hospital
- **THEN** ese hospital se incluye en la lista con `valoracionPromedio` igual al puntaje promedio de Google (entre 1.0 y 5.0) y `cantidadValoraciones` igual a la cantidad de reseñas

#### Scenario: Hospital sin valoración en Google Places
- **WHEN** el paciente solicita los hospitales cercanos y Google Places no provee valoración para un hospital
- **THEN** el hospital se incluye en la lista igualmente y su campo de valoración ausente se devuelve como `null` sin provocar un error

#### Scenario: Datos de valoración incompletos de Google
- **WHEN** Google Places provee la cantidad de valoraciones pero no el puntaje promedio (o al revés) para un hospital
- **THEN** el campo no provisto se devuelve como `null` y el provisto con su valor, sin provocar un error

### Requirement: El sistema permite ordenar los hospitales por valoración
El sistema SHALL aceptar `ordenarPor=valoracion` en `GET /api/hospitales/cercanos` y MUST ordenar el listado por `valoracionPromedio` de mayor a menor, tratando los hospitales sin valoración (`null`) como puntaje 0 para la ordenación, con `cantidadValoraciones` como criterio de desempate (mayor a menor) y el nombre del hospital (el devuelto por Google Places) como desempate final ordenado ASC; los hospitales con el mismo nombre (o sin nombre) MUST conservar entre sí el orden en que los devolvió Google Places. Los hospitales sin valoración MUST quedar al final del ordenamiento, sin ser excluidos del listado. El parámetro `ordenarPor` es opcional: cuando se omite o viene vacío MUST usarse el criterio por defecto `distancia`. El valor `valoracion` MUST aceptarse exactamente en minúsculas: cualquier variante de mayúsculas o escritura distinta MUST responder 400.

#### Scenario: Ordenar por valoración con y sin datos
- **WHEN** el paciente solicita `ordenarPor=valoracion`
- **THEN** los hospitales con mayor puntaje promedio aparecen primero, los empates se resuelven por mayor cantidad de valoraciones y luego por nombre ASC, y los hospitales sin valoración aparecen al final

#### Scenario: Orden por valoración no vacía el resultado
- **WHEN** el paciente solicita `ordenarPor=valoracion` y ningún hospital de la zona tiene valoración registrada
- **THEN** el listado devuelve los mismos hospitales que cualquier otro criterio de orden (nunca una lista vacía por falta de valoraciones)

#### Scenario: Variante con mayúsculas rechazada
- **WHEN** el paciente solicita `ordenarPor=Valoracion` (o cualquier variante distinta de `valoracion` en minúsculas)
- **THEN** el sistema responde 400

#### Scenario: Valoración inválida rechazada
- **WHEN** el paciente solicita `ordenarPor=valoracion-invalida`
- **THEN** el sistema responde 400

#### Scenario: Sin criterio de orden se usa el default
- **WHEN** el paciente solicita el listado sin el parámetro `ordenarPor` o con `ordenarPor` vacío
- **THEN** el listado se ordena por `distancia` (criterio por defecto)

### Requirement: El criterio de valoración se combina con los demás criterios de orden
El sistema SHALL aceptar combinaciones de `valoracion` con `distancia` y `tiempo-atencion` en `ordenarPor` separadas por `|` (por ejemplo `valoracion|distancia` o `distancia|tiempo-atencion|valoracion`), ordenadas indistintamente, aplicando la misma semántica de suma de rankings que los criterios ya existentes sobre el listado ya filtrado por especialidad y disponibilidad. Si cualquier criterio de la combinación es inválido o está duplicado (incluidas variantes de mayúsculas distintas de los valores exactos en minúsculas), el sistema MUST responder 400 aunque los demás criterios sean válidos. Cuando la suma de rankings empate entre hospitales, el sistema MUST desempatar por nombre del hospital (el devuelto por Google Places) ASC y, con nombres iguales o ausentes, MUST conservar el orden en que los devolvió Google Places.

#### Scenario: Combinación de valoración con distancia
- **WHEN** el paciente solicita `ordenarPor=valoracion|distancia`
- **THEN** el listado se ordena por la suma de rankings de ambos criterios: posición por valoración y posición por distancia

#### Scenario: Combinación de los tres criterios
- **WHEN** el paciente solicita `ordenarPor=distancia|tiempo-atencion|valoracion`
- **THEN** el listado se ordena por la suma de rankings de los tres criterios

#### Scenario: Combinación con un criterio inválido rechazada
- **WHEN** el paciente solicita `ordenarPor=valoracion|foo` (un criterio válido y otro inválido)
- **THEN** el sistema responde 400
