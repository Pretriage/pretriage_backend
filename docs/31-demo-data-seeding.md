# Carga de datos para la demo

`scripts/seed_demo.py` prepara datos de muestra para validar los tableros y los
flujos de la demo. El script está listo, pero **todavía no se ejecutó contra
Auth0 ni contra la base AWS**. El entorno desplegado puede contener usuarios y
datos cargados manualmente; publicar este script no ejecuta ninguna carga.
La aplicación de datos queda expresamente diferida por pedido del usuario.

## Qué genera

- Ocho establecimientos privados de CABA con nombre, dirección y coordenadas
  tomados de [Buenos Aires Data — Centros de Salud Privados](https://data.buenosaires.gob.ar/es_AR/dataset/centros-salud-privados): Hospital Alemán,
  Hospital Italiano, Hospital Británico de Buenos Aires, Fundación Favaloro,
  Sanatorio de los Arcos, Sanatorio Colegiales, Sanatorio Güemes y CEMIC — Sede
  Saavedra. El catálogo ya incluye sus ocho Place IDs, resueltos el 31/10/2026;
  Google devuelve dos identificadores para Güemes y se eligió el de Sanatorio
  Güemes en Av. Córdoba 3933, cercano al punto del catálogo oficial.
- Cinco especialidades configuradas por hospital, con un sector y dos salas
  por especialidad.
- Veintinueve identidades Auth0: un administrador global, un administrador de
  hospitales, ocho recepcionistas, dieciséis médicos y tres pacientes.
- Historial completamente ficticio: 30 días hasta ayer en la primera carga,
  con una referencia de 12 consultas diarias por hospital y variación por fecha
  y fines de semana. Para el 1–30/10/2026 se generan 2.610 consultas, con casos
  finalizados y cancelados, distintos tiempos de espera, con/sin pretriage y
  revisiones médicas coincidentes o distintas. Se crean hasta 80 pacientes
  históricos por hospital; no son cuentas de Auth0. El periodo y el volumen
  pueden variarse con las opciones del script.

Las ubicaciones públicas corresponden a los establecimientos; las salas,
sectores, usuarios, especialidades habilitadas, sesiones y atenciones son datos
sintéticos. La carga no afirma capacidad, disponibilidad ni servicios clínicos
reales. Hay médicos demo con sesión activa solo en Clínica Médica y
Traumatología; las otras tres especialidades tienen actividad histórica, pero
no disponibilidad actual mientras no tengan un médico demo activo.

## Vista previa y aplicación local

Desde la raíz del repositorio, la vista previa predeterminada no accede a la red
ni modifica Auth0, Google, PostgreSQL o AWS:

```powershell
python scripts/seed_demo.py
```

Para aplicar a PostgreSQL local, primero asegurate de que el backend haya
creado el esquema. El archivo de variables indicado debe contener la
configuración M2M de Auth0; el script lee el archivo sin imprimir sus valores.
`GOOGLE_MAPS_API_KEY` se necesita solo para refrescar identificadores o resolver
un catálogo personalizado sin Place IDs. Se solicitan únicamente IDs en Text
Search; no se guardan valoraciones ni datos clínicos de Google.
[Identificadores de Places](https://developers.google.com/maps/documentation/places/web-service/place-id).

```powershell
python scripts/seed_demo.py --apply --target docker `
  --db-container postgre --db-user myuser --db-name pretriage_db `
  --env-file target/aws-deploy/runtime/.env.aws
```

## Aplicación en AWS

Instalá las dependencias opcionales para AWS y usá un perfil ya configurado:

```powershell
python -m pip install -r scripts/requirements-demo.txt
python scripts/seed_demo.py --apply --target aws --profile <perfil> `
  --env-file target/aws-deploy/runtime/.env.aws --verify-logins
```

El destino AWS exige que la instancia existente ya esté encendida y Online en
SSM. La herramienta verifica el stack, la instancia y el esquema antes de crear
usuarios Auth0. No inicia ni detiene EC2 ni crea infraestructura. Envía el SQL
comprimido al bucket S3 privado del stack, lo ejecuta por SSM dentro del
contenedor PostgreSQL y elimina la versión temporal exacta del objeto al
terminar. No expone el puerto de la base.

El tenant de Auth0 debe tener habilitada la conexión configurada por
`AUTH0_DEMO_CONNECTION` (por defecto
`Username-Password-Authentication`). La aplicación M2M necesita los permisos
`read:users` y `create:users`. Los correos se generan bajo
`demo.pretriage.com.ar` y se crean sin envío de verificación; las políticas del
tenant pueden impedir iniciar sesión con una cuenta cuyo correo no esté
verificado. `--verify-logins` comprueba el acceso de las 29 cuentas después de
aplicar el SQL sin mostrar ni guardar tokens. En AWS usa por defecto
`https://api.pretriage.com.ar`; en Docker local usa `http://localhost:8080`.
`--backend-url` permite indicar otra URL para esa comprobación.

## Estado privado e idempotencia

Por defecto el estado se guarda en
`target/demo-seed/demo-caba-v1/state.json`, dentro de un directorio protegido e
ignorado por Git. Incluye contraseñas generadas y Place IDs; no las copies a
logs, commits ni mensajes. Conservá ese archivo aunque recrees PostgreSQL: así
el script reutiliza las mismas identidades y contraseñas de Auth0. No borres el
estado privado al reiniciar o reconstruir la base.

Una misma `seed_key` solo puede reutilizarse con la configuración original; el
hash de entradas evita aplicar datos distintos bajo una clave existente. Repetir
la misma carga con la misma clave y estado reutiliza las identidades y no duplica
filas. Los parámetros históricos de la última carga exitosa se reutilizan
cuando se omiten; volver a ejecutar mañana no desplaza automáticamente las fechas.

Podés ajustar el periodo y el volumen antes de la primera aplicación, por
ejemplo `--days 30 --daily-per-hospital 12 --history-patients 80`. El fin del
periodo predeterminado es ayer en la primera ejecución. Una vez aplicada una
combinación de entradas, los valores deben mantenerse para repetirla con esa
misma clave. Si se recrea PostgreSQL, conservá el estado y reutilizá la misma
clave para recuperar las cuentas. En esa base nueva podés indicar explícitamente
otra ventana con `--end-date YYYY-MM-DD` sin crear nuevas cuentas Auth0. No se
ofrece una opción de borrado o reset de datos desde este script.

`--seed-key` permite mantener otra demo independiente con su propio estado y
otra base vacía. No mezclar varias claves en una misma base: las identidades y
documentos sintéticos están diseñados para una población de demo por base.

La selección de hospitales aplica el radio de 5 km del backend. Para probarla,
usá una ubicación de CABA próxima a los establecimientos cargados.

## Alcance de la verificación

La validación realizada hasta ahora cubrió pruebas aisladas del generador y una
prueba PostgreSQL desechable de persistencia e idempotencia. También se
comprobó que el cliente M2M configurado ya obtiene `read:users create:users` y
que el directorio de credenciales puede restringirse al usuario en Windows.
No se crearon usuarios
reales en Auth0 ni cargó datos en la base desplegada. La verificación de inicio
de sesión con las cuentas demo queda pendiente hasta que se autorice y ejecute
la carga.

Comandos de verificación:

```powershell
python -B -m unittest discover -s scripts -p "test_demo_seed*.py" -v
python scripts/test_backend_isolated.py --test DemoSeedScriptPersistenceTest
```

GitHub Actions también ejecuta las pruebas Python sin acceso a servicios y la
suite Java contra un PostgreSQL descartable llamado `pretriage_test_ci`, con
datasource explícito y Docker Compose de Spring deshabilitado. La prueba de
persistencia rechaza nombres de bases que no sean de pruebas aisladas y ejecuta
el cuerpo del SQL dentro de la transacción del test, verificando después del
rollback que no queden hospitales ni tablas de control del fixture.
