# Publicación del backend desde GitHub

La rama de publicación es `produccion`. Apunta a la demo existente de EC2, ECR,
PostgreSQL y S3; no crea un segundo entorno ni convierte la demo en un despliegue
productivo. La organización recibió los cambios consolidados de `desarrollo`
mediante el PR #1; la primera promoción a `produccion` se realiza mediante PR.

## Flujo de ramas y comprobaciones

El repositorio es `Pretriage/pretriage_backend`; su rama predeterminada es
`desarrollo`, donde también debe existir el workflow para habilitar la ejecución
manual. El remoto local `origin` apunta a la organización; `legacy` conserva la
referencia al repositorio anterior.

- PR hacia `desarrollo`, `main` o `produccion`: compilar, ejecutar la suite Java
  en PostgreSQL descartable, probar el generador de datos sin servicios y probar
  las salvaguardas del despliegue con dobles de AWS/Docker.
- Push o merge a `desarrollo` y `main`: las mismas verificaciones, sin despliegue.
- Push o merge a `produccion`: verificaciones, construcción Linux amd64 fuera de
  EC2, publicación en ECR y actualización de backend por Systems Manager.
- Ejecución manual de `Backend CI` con la rama `produccion`: permite reintentar
  después de encender la demo. En otras ramas solo ejecuta las verificaciones.

Los jobs de publicación y despliegue dependen de las verificaciones. Una etiqueta
incluye commit, ejecución e intento; EC2 utiliza la imagen por digest `sha256`.
Las actualizaciones de `produccion` no cancelan una ejecución en curso.

## Protección de produccion

El propietario o un administrador debe aplicar
`infra/github/production-branch-protection.json`. Exige PR, check `build-and-test`
aprobado, rama actualizada y conversaciones resueltas. Incluye administradores y
bloquea force-push y eliminación. No exige la aprobación de una segunda persona;
el requisito es que los cambios entren mediante PR y pasen las verificaciones.

Un administrador puede aplicar la configuración con su propia sesión:

```powershell
gh api --method PUT repos/Pretriage/pretriage_backend/branches/produccion/protection `
  --input infra/github/production-branch-protection.json
```

La sesión de administración de la organización permite aplicar esta protección.
El repositorio es público y la regla está disponible con GitHub Free.
El workflow comprueba que GitHub marque `produccion` como protegida antes de
autenticarse con AWS y publicar una imagen; esa comprobación no sustituye la
revisión de todos los campos de la regla por el administrador.

## Autenticación con AWS

`infra/aws/github-deploy-stack.yaml` crea un rol OIDC para GitHub y, únicamente
si no existe, el proveedor OIDC. Su confianza exige audiencia `sts.amazonaws.com`
y el subject exacto del repositorio para `refs/heads/produccion`. No utiliza un
environment de GitHub, ya que eso cambiaría el subject del token.

El 04/11/2026 la API de GitHub informó `use_default=true`,
`use_immutable_subject=true` y prefijo
`repo:Pretriage@350848470/pretriage_backend@1441221730`.
Si el repositorio cambia de propietario/nombre o activa subjects con IDs
inmutables, actualizar la confianza a partir de su configuración real.

El rol puede cargar imágenes solo al repositorio ECR configurado y enviar
`AWS-RunShellScript` solo a la instancia configurada. SSM ejecuta el script con
privilegios de administración del host, por lo que la protección de la rama
es parte del control de acceso. El rol no concede inicio/parada de EC2,
administración IAM ni acceso directo a PostgreSQL o al bucket clínico.

El bootstrap se realiza desde una sesión local autorizada, en la región asignada
del proyecto (`us-east-1` en esta demo):

```powershell
$awsProfile = 'tu-perfil'
aws iam list-open-id-connect-providers --profile $awsProfile
aws cloudformation describe-stacks --stack-name pretriage-demo `
  --profile $awsProfile --region us-east-1 --query 'Stacks[0].Outputs'
```

Tomar `InstanceId` y el nombre de repositorio de `BackendImageRepositoryUri`.
Si ya existe `token.actions.githubusercontent.com`, pasar su ARN como
`ExistingGitHubOidcProviderArn`; no adoptar ni reemplazar un proveedor administrado
por otro stack. Si no existe, omitir ese parámetro.

```powershell
aws cloudformation deploy --template-file infra/aws/github-deploy-stack.yaml `
  --stack-name pretriage-github-deploy --capabilities CAPABILITY_IAM `
  --parameter-overrides 'InstanceId=<instancia>' 'EcrRepositoryName=<nombre>' `
    'GitHubSubjectPrefix=repo:Pretriage@350848470/pretriage_backend@1441221730' `
  --profile $awsProfile --region us-east-1
```

El stack no contiene recursos de cómputo, base de datos, bucket ni red.
CloudTrail Event history proporciona el historial reciente; no se incorpora
un trail de retención duradera con este cambio.

Configurar estas **repository variables**, no claves estáticas de AWS:

| Variable | Valor |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | Salida `DeploymentRoleArn` del stack de CI/CD |
| `ECR_REPOSITORY_URI` | Salida `RepositoryUri` del stack de CI/CD |
| `EC2_INSTANCE_ID` | Salida `InstanceId` del stack de CI/CD |

Usar `gh variable set <NOMBRE> --repo Pretriage/pretriage_backend --body <VALOR>`
o Settings → Secrets and variables → Actions → Variables.

## Actualización y recuperación

`scripts/github_deploy.py` verifica que la instancia esté encendida y Online en
SSM antes de enviar el comando. Si está apagada o desconectada, la imagen queda
publicada, el job de despliegue falla con una explicación y no se afirma que el
backend esté actualizado. Encender manualmente y reejecutar el workflow de
`produccion`; el arranque normal de EC2 conserva la imagen que ya tenía configurada.

Los [horarios diarios de EC2](33-aws-instance-schedule.md) se administran mediante
otro stack: encienden a las 10:00 y apagan a las 22:00 de Buenos Aires. Este rol
de GitHub conserva sus permisos sin inicio/parada; el Scheduler usa su propio rol.
El apagado programado puede interrumpir un despliegue, por lo que conviene promover
cambios con margen antes de las 22:00.

El script Bash descargará primero la imagen, conservará el archivo de entorno y
actualizará únicamente `BACKEND_IMAGE`. El valor previo admite comillas simples
o dobles y finales de línea CRLF, sin evaluar expresiones de shell. Recrea solo
backend, espera su healthcheck y verifica readiness interno, imagen efectiva y
acceso HTTPS público. Si falla
la actualización, restaura la configuración y vuelve a la imagen anterior local.
El resultado sigue siendo fallido aunque esa recuperación tenga éxito.

Esta recuperación **no revierte cambios de esquema ni datos**. El perfil actual
usa Hibernate `update`; revisar por separado cualquier cambio de entidades que
pueda hacer incompatible la versión anterior y respaldar los datos antes de esa
promoción. El workflow no ejecuta `initial-data.sql`, `metricas-data.sql` ni
`seed_demo.py`. La carga de la base sigue diferida.

El contenedor se reemplaza en el mismo host: hay una interrupción breve y los
sockets conectados se cortan. La actualización no valida por sí sola una sesión
de voz con Gemini; sigue siendo necesaria una prueba funcional posterior.

## Validación y estado

```powershell
python -B -m unittest discover -s scripts -p 'test_*deploy*.py'
```

Los tests de Bash usan comandos falsos en Linux y no operan recursos reales.
En Windows pueden quedar omitidos; GitHub Actions los ejecuta en Ubuntu.
La configuración AWS, la protección y el primer despliegue deben confirmarse
antes de considerar operativo el circuito completo; un PR aprobado o una
imagen publicada por sí solos no acreditan un despliegue en EC2.
