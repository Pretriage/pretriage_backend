# Spec Delta

## Purpose

Establece la relación consultable entre una consulta médica y el chat de pretriage que la generó, y permite que un paciente tenga varios chats a lo largo del tiempo, condición necesaria para medir la adopción del pretriage.

## ADDED Requirements

### Requirement: Vínculo consulta-chat
El sistema SHALL mantener un vínculo entre una `ConsultaMedica` y el `Chat` de pretriage del chatbot. El vínculo MUST establecerse únicamente cuando el chatbot finaliza el chat e ingresa al paciente a la cola con su nivel de prioridad, y dos consultas distintas no MUST compartir el mismo chat.

#### Scenario: Chatbot finaliza el pretriage
- **WHEN** el chatbot finaliza el chat de un paciente que ya seleccionó hospital y lo ingresa a la cola con la prioridad estimada
- **THEN** la consulta médica de ese paciente queda vinculada al chat finalizado

#### Scenario: Selección de hospital sin chat
- **WHEN** un paciente selecciona un hospital e ingresa a la cola directamente sin usar el chatbot
- **THEN** su consulta médica queda sin chat vinculado

#### Scenario: Chat abierto no vinculado
- **WHEN** un chat se finaliza por cancelación de la atención sin haber ingresado al paciente a la cola con prioridad del bot
- **THEN** ninguna consulta médica queda vinculada a ese chat

### Requirement: Varios chats por paciente
El sistema SHALL permitir que un paciente tenga más de un chat a lo largo del tiempo. Al iniciar un chat, el sistema MUST crear siempre uno nuevo y MUST cerrar los chats abiertos (sin finalizar) previos del paciente. El sistema MUST rechazar la creación con un error 400 y un mensaje apto para el paciente cuando exista un chat vinculado a una consulta médica cuya `EntradaCola` correspondiente esté en un estado distinto de `FINALIZADA` o `CANCELADA`; en ese caso MUST abstenerse de crear el chat y de modificar los chats previos.

#### Scenario: Siempre chat nuevo
- **WHEN** un paciente sin atención pendiente solicita iniciar un chat
- **THEN** el sistema crea un chat nuevo y los chats abiertos que tuviera quedan finalizados

#### Scenario: Bloqueo por atención pendiente
- **WHEN** un paciente cuya consulta médica tiene un chat vinculado y una `EntradaCola` en estado `EN_COLA` (u otro estado no terminal) solicita iniciar un chat
- **THEN** el sistema responde 400 con un mensaje que indica que ya tiene una atención pendiente, no crea el chat y no modifica sus chats previos

#### Scenario: Nuevo chat con entrada finalizada
- **WHEN** un paciente cuya consulta previa con chat vinculado tiene la `EntradaCola` en estado `FINALIZADA` solicita iniciar un chat
- **THEN** el sistema crea un chat nuevo

#### Scenario: Nuevo chat con entrada cancelada
- **WHEN** un paciente cuya consulta previa con chat vinculado tiene la `EntradaCola` en estado `CANCELADA` solicita iniciar un chat
- **THEN** el sistema crea un chat nuevo

#### Scenario: Segundo chat tras primera consulta
- **WHEN** un paciente ya atendido en una consulta previa inicia un nuevo proceso de pretriage
- **THEN** el sistema le permite crear un segundo chat sin error de restricción única
