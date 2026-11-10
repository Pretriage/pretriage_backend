# Carga de datos para la demo

Los seeds del backend mantienen los nombres de catálogo con tildes; la
migración `scripts/migrations/20261110_normalizar_nombres_especialidades.sql`
actualiza únicamente los cinco nombres legacy conocidos, preservando códigos,
IDs y asociaciones.

`scripts/seed_demo.py` prepara datos de muestra para validar los tableros y los
flujos de la demo. El 05/11/2026 se aplicó en AWS con `seed_key`
`demo-caba-v1`, tras renovar la sesión de AWS. La instancia ya estaba encendida
por su horario diario; durante la carga no se inició ni detuvo EC2.

La carga confirmó en PostgreSQL:

- 8 hospitales y 16 obras sociales.
- 29 identidades nuevas de Auth0; quedaron 32 filas en `usuario_auth`, pues se
  conservaron 3 existentes.
- 646 pacientes: 640 históricos, 3 asociados a las cuentas demo y 3 previos.
- 2.600 consultas y entradas de cola; 2.208 atenciones finalizadas y 392
  canceladas.
- 1.716 chats con pretriage, 40 sectores, 80 salas, 32 membresías, 16 sesiones
  activas (Clínica Médica y Traumatología) y 480 sesiones históricas finalizadas.

La verificación posterior confirmó que `GET /api/obrasocial` devuelve 16 y que
una cuenta de administración hospitalaria puede consultar métricas de los ocho
hospitales para ese periodo. La API devolvió los totales esperados: 2.600
ingresos, 2.208 atendidos, 392 no atendidos y 1.716 con pretriage.

El periodo generado fue del 06/10 al 04/11/2026. El backup PostgreSQL previo a
la carga permanece en EC2 en
`/opt/pretriage/backups/pre-seed-20261105.XSUICz.sql.gz`, con modo `600`; se
verificó que el archivo gzip sea legible. El objeto temporal S3 usado para la
carga se eliminó por su versión exacta.

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
- Catálogo compartido de 16 obras sociales: OSDE, SWISS MEDICAL, GALENO, MEDIFE,
  OMINT, SANCOR SALUD, ACCORD SALUD, UNION PERSONAL, OSECAC, OSDEPYM, OSPE,
  OSUTHGRA, OSPERYH, IOMA, PAMI y DOSUBA. Lo carga el mismo comando integral;
  no hace falta ejecutar por separado `seed_dev_obras_sociales.py`.
- Veintinueve identidades Auth0: un administrador global, un administrador de
  hospitales, ocho recepcionistas, dieciséis médicos y tres pacientes.
- Historial completamente ficticio: 30 días hasta ayer en la primera carga,
  con una referencia de 12 consultas diarias por hospital y variación por fecha
  y fines de semana. En la carga AWS del 05/11/2026 se generaron 2.600 consultas
  para el periodo 06/10–04/11/2026, con casos
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
aplicar el SQL sin mostrar ni guardar tokens. La primera prueba del verificador
recibió HTTP 403 con el agente genérico de Python; al identificar el cliente
como `PretriageDemoSeed/1.0`, las 29 cuentas completaron el login por HTTPS y
devolvieron el subject esperado. En AWS usa por defecto
`https://api.pretriage.com.ar`; en Docker local usa `http://localhost:8080`.
`--backend-url` permite indicar otra URL para esa comprobación.

## Estado privado e idempotencia

El catálogo de obras sociales se aplica dentro de la misma transacción SQL.
Compara nombres sin distinguir mayúsculas ni espacios en los extremos y agrega
solo los faltantes con `virgente=true`; conserva los IDs, nombres, bajas y
registros ajenos existentes. Comparte catálogo y lógica con el helper local,
por lo que pueden alternarse sin agregar duplicados. No se crean credenciales,
planes ni números de afiliado para pacientes con esta carga de catálogo.
La tabla `obra_social` forma parte del preflight, previo a crear usuarios Auth0.
Las demos ya generadas pueden incorporar este catálogo al repetir `--apply`
con sus mismos parámetros y estado; no se cambia el hash de sus entradas.

Por defecto el estado se guarda en
`target/demo-seed/demo-caba-v1/state.json`, dentro de un directorio protegido e
ignorado por Git. Incluye contraseñas generadas y Place IDs; no las copies a
logs, commits ni mensajes. Para evitar perderlo al limpiar `target`, se guardó
una copia privada duradera en
`C:/Users/valen/.pretriage/demo-seed/demo-caba-v1/state.json`, con ACL limitada
al usuario. Conservá esa copia para reutilizar las mismas identidades y
contraseñas de Auth0. No borres el estado privado al reiniciar o reconstruir la
base.

Para futuras ejecuciones desde PowerShell, usá la copia duradera mediante
`--state-dir`:

```powershell
python scripts/seed_demo.py --apply --target aws --profile <perfil> `
  --env-file target/aws-deploy/runtime/.env.aws `
  --state-dir "$env:USERPROFILE\.pretriage\demo-seed\demo-caba-v1" --verify-logins
```

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

La validación cubrió pruebas aisladas del generador, una prueba PostgreSQL
desechable de persistencia e idempotencia, 38 pruebas Python aprobadas y la
carga real en AWS descrita arriba.
También se comprobó que el cliente M2M configurado obtiene `read:users
create:users` y que el directorio de credenciales puede restringirse al usuario
en Windows. Las 29 cuentas completaron el login HTTP 200 y la comparación de
subject tras identificar el cliente del verificador.

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
