# Horarios diarios para la instancia de demo

Esta guía administra el encendido y apagado diario de la instancia EC2 de Pretriage mediante **Amazon EventBridge Scheduler**. El horario usa la zona `America/Argentina/Buenos_Aires`: inicia todos los días a las 10:00 y solicita una parada normal todos los días a las 22:00. No usa Lambda.

La instancia existente es `i-02cb7756f580fa419` en `us-east-1`, con Elastic IP `52.1.161.207`. El servicio `pretriage.service` está habilitado para iniciar Docker, PostgreSQL, el backend y Caddy cuando arranca el sistema. Tras el encendido de EC2, esos servicios pueden tardar unos minutos en quedar disponibles.

Los horarios se disparan con precisión de minuto, no garantizan que la API esté lista exactamente a las 10:00. La parada solicita detener el sistema operativo; no fuerza la detención, termina la instancia ni borra datos. El volumen EBS y la Elastic IP permanecen asociados. Al detener EC2 deja de cobrarse el cómputo, pero EBS y la IPv4 reservada siguen generando cargos. Amplify y S3 no se ven afectados.

## Desplegar los horarios

Usá AWS CLI v2 y un perfil autenticado con permisos para administrar CloudFormation, EventBridge Scheduler y el rol de IAM que crea la plantilla. Confirmá que la región asignada al proyecto sea `us-east-1`; no despliegues recursos regionales en otra región.

```powershell
$awsProfile = 'tu-perfil'
$awsRegion = 'us-east-1'
$stackName = 'pretriage-demo-schedules'
$instanceId = 'i-02cb7756f580fa419'

aws cloudformation validate-template `
  --template-body file://infra/aws/instance-schedule-stack.yaml `
  --profile $awsProfile --region $awsRegion --no-cli-pager

aws cloudformation deploy `
  --template-file infra/aws/instance-schedule-stack.yaml `
  --stack-name $stackName `
  --capabilities CAPABILITY_IAM `
  --parameter-overrides "InstanceId=$instanceId" "ScheduleState=ENABLED" `
  --profile $awsProfile --region $awsRegion
```

El stack crea un grupo de horarios con el mismo nombre que el stack, un horario de inicio a las 10:00 y otro de parada a las 22:00. Ambos funcionan todos los días, incluidos fines de semana. La plantilla configura `FlexibleTimeWindow=OFF`, una antigüedad máxima del evento de 300 segundos y dos reintentos. La expresión cron se interpreta en la zona horaria indicada por la plantilla.

El rol de Scheduler solo puede llamar `ec2:StartInstances` y `ec2:StopInstances` sobre el ID de instancia indicado. El horario de parada usa el objetivo normal de StopInstances: no fuerza la detención ni elimina la instancia o sus volúmenes.

Consultá si CloudFormation completó el despliegue:

```powershell
aws cloudformation describe-stacks `
  --stack-name $stackName `
  --query 'Stacks[0].[StackStatus,StackStatusReason]' `
  --output table --profile $awsProfile --region $awsRegion
```

## Pausar y reanudar

Para pausar la automatización, actualizá **los dos horarios** a `DISABLED`:

```powershell
aws cloudformation deploy `
  --template-file infra/aws/instance-schedule-stack.yaml `
  --stack-name $stackName `
  --capabilities CAPABILITY_IAM `
  --parameter-overrides "InstanceId=$instanceId" "ScheduleState=DISABLED" `
  --profile $awsProfile --region $awsRegion
```

Para reanudar ambos horarios, volvé a desplegar con `ScheduleState=ENABLED`:

```powershell
aws cloudformation deploy `
  --template-file infra/aws/instance-schedule-stack.yaml `
  --stack-name $stackName `
  --capabilities CAPABILITY_IAM `
  --parameter-overrides "InstanceId=$instanceId" "ScheduleState=ENABLED" `
  --profile $awsProfile --region $awsRegion
```

Deshabilitar horarios impide sus invocaciones futuras; no cambia por sí mismo el estado actual de EC2. Antes de dejar de usar la demo, pausá los dos horarios y detené la instancia para evitar que vuelva a iniciarse al día siguiente.

## Ver el estado y detener manualmente

Consultar el estado de la instancia:

```powershell
aws ec2 describe-instances `
  --instance-ids $instanceId `
  --query 'Reservations[0].Instances[0].[InstanceId,State.Name,PublicIpAddress]' `
  --output table --profile $awsProfile --region $awsRegion
```

Para solicitar una parada normal fuera del horario automático:

```powershell
aws ec2 stop-instances `
  --instance-ids $instanceId `
  --profile $awsProfile --region $awsRegion
```

Una parada manual no deshabilita los horarios: el siguiente horario de las 10:00 volverá a iniciar la instancia. Para dejar la demo apagada, pausá primero los dos horarios y luego detené EC2 si está encendida.

## Efectos en los despliegues

Los pipelines de CI/CD publican y despliegan el backend, pero no inician EC2. Si una ejecución necesita conectarse a la instancia mientras está detenida, puede fallar; iniciá la instancia y esperá a que `pretriage.service` recupere los servicios. El inicio automático del sistema no cambia la política de datos ni recrea la base PostgreSQL.

## Referencias

- [Tipos de horario y expresiones cron de EventBridge Scheduler](https://docs.aws.amazon.com/scheduler/latest/UserGuide/schedule-types.html)
- [Detener e iniciar instancias EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/Stop_Start.html)
