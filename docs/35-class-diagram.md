# Diagrama de clases del backend Pretriage

La [fuente PlantUML](35-class-diagram.puml) reúne la vista general y las cuatro vistas de detalle en un archivo.

El UML cubre las 31 entidades del [modelo de dominio Java](../src/main/java/com/pretriage/backend/model) y la clase abstracta `RegistroCambioContrasenia`. Las vistas de detalle muestran atributos y operaciones seleccionados. Los tipos de estado aparecen en los atributos que los usan.

Las multiplicidades de las vistas de detalle reflejan los mapeos JPA. `0..1` indica una asociación opcional, `1` una obligatoria y `0..*` una colección. Algunas asociaciones opcionales pasan a ser necesarias cuando una consulta avanza en el flujo.

## 1. Vista general

`ConsultaMedica` conecta los dos caminos de ingreso con la cola y la atención. El ingreso digital puede vincular un `Chat`, mientras que recepción registra una `AdmisionRecepcion` en su propia sesión.

## 2. Consultas, cola y atención médica

Una consulta en cola tiene una `EntradaCola` y un sector asignado. El gestor se identifica por hospital, especialidad y sector. `consultasEnEspera` es una colección del flujo anterior. La estimación usa las entradas en estado `EN_COLA` y las sesiones médicas activas.

## 3. Pretriage digital y admisión por recepción

Un paciente puede tener varios chats a lo largo del tiempo. La consulta puede vincular uno tras una clasificación completa o un [cierre parcial](34-automatic-chat-recovery.md). Recepción guarda el formulario y su resultado en `AdmisionRecepcion`, con una asociación obligatoria a la consulta y a la sesión de recepción.

## 4. Hospitales, pacientes y cobertura

Cada hospital habilita un conjunto de especialidades. Los sectores agrupan salas por hospital y especialidad. Las credenciales vinculan al paciente con una obra social y los estudios forman parte de su historial clínico.

## 5. Identidad y acceso hospitalario

`UsuarioAuth` vincula la identidad autenticada con los perfiles. El paciente registrado en recepción puede existir sin esa identidad. Las membresías y las invitaciones se gestionan por hospital. El token de restablecimiento y el bloqueo por intentos fallidos heredan campos de `RegistroCambioContrasenia`.

## Renderizar las cinco vistas

El render se verificó con PlantUML 1.2026.9 y Java 21. Con `plantuml.jar` en la raíz del repositorio y Java 21 en el `PATH`, ejecuta este comando.

```powershell
java -jar plantuml.jar -charset UTF-8 -tsvg -o "$env:TEMP\pretriage-uml" "docs\35-class-diagram.puml"
```
