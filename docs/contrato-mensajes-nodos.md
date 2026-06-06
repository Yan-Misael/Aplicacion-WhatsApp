# Contrato de mensajes entre nodos

## Propósito del documento

Este documento define el contrato lógico de comunicación entre los `ServerNode` de la Entrega Final del sistema de mensajería instantánea inspirado en WhatsApp.

La Entrega Final evoluciona desde una arquitectura Cliente-Servidor Broker centralizada hacia una arquitectura multiservidor. En esta nueva arquitectura, cada nodo servidor acepta clientes locales y, además, se comunica con otros nodos servidores para enrutar mensajes, replicar información mínima, detectar fallos y coordinar recursos compartidos.

Este contrato busca dejar explícito:

- qué mensajes pueden enviarse entre nodos;
- qué campos mínimos debe tener cada mensaje;
- qué componente procesa cada tipo de mensaje;
- qué pool de hilos debe utilizarse;
- qué mensajes participan en enrutamiento, membresía, fallos y coordinación;
- qué relación existe entre los mensajes y los relojes de Lamport;
- qué errores deben manejarse de forma controlada.

---

## Principios generales del contrato

La comunicación entre nodos debe cumplir los siguientes principios:

1. Todo mensaje inter-nodo debe ser serializable.
2. Todo mensaje debe identificar nodo origen y nodo destino.
3. Todo mensaje debe incluir un identificador único.
4. Todo mensaje distribuido relevante debe incluir timestamp de Lamport.
5. El tiempo físico no debe usarse para ordenar eventos distribuidos.
6. Los mensajes de clientes no deben enviarse directamente a otros clientes remotos.
7. Todo reenvío remoto debe pasar por `ServerNode`.
8. Los mensajes de coordinación y heartbeats no deben depender del pool de clientes.
9. Los errores remotos deben responderse con mensajes explícitos o logs controlados.
10. Los mensajes duplicados deben poder detectarse mediante `messageId`.

---

## Clase base conceptual

Todos los mensajes entre nodos deben heredar o cumplir conceptualmente con la siguiente estructura base:

~~~java
public abstract class NodeMessage implements Serializable {
    private String messageId;
    private String sourceNodeId;
    private String targetNodeId;
    private NodeMessageType type;
    private long lamportTimestamp;
    private long sentAtMillis;
}
~~~

---

## Campos base obligatorios

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---:|---|
| `messageId` | `String` | Sí | Identificador único del mensaje |
| `sourceNodeId` | `String` | Sí | Nodo que envía el mensaje |
| `targetNodeId` | `String` | Sí | Nodo destino del mensaje |
| `type` | `NodeMessageType` | Sí | Tipo lógico del mensaje |
| `lamportTimestamp` | `long` | Sí | Marca lógica de Lamport |
| `sentAtMillis` | `long` | Sí | Tiempo físico local usado solo para métricas |

---

## Reglas sobre `messageId`

El campo `messageId` debe permitir detectar duplicados y correlacionar respuestas.

Formato recomendado:

~~~text
sourceNodeId + "-" + sequenceNumber + "-" + UUID
~~~

Ejemplo:

~~~text
node1-42-550e8400-e29b-41d4-a716-446655440000
~~~

Uso esperado:

- evitar procesar dos veces un mismo mensaje;
- asociar ACKs con mensajes enviados;
- registrar trazabilidad en logs;
- calcular métricas de latencia;
- depurar omisiones o reintentos.

---

## Reglas sobre `sourceNodeId` y `targetNodeId`

Cada nodo debe tener un identificador lógico estable:

~~~text
node1
node2
node3
~~~

No debe usarse la IP como identidad principal del nodo.

La IP y el puerto pertenecen a `NodeInfo`, pero la identidad lógica del nodo debe ser el `nodeId`.

Ejemplo:

~~~text
sourceNodeId = node1
targetNodeId = node2
~~~

Para mensajes broadcast entre nodos, se puede usar:

~~~text
targetNodeId = *
~~~

Sin embargo, se recomienda que el emisor envíe un mensaje individual a cada nodo cuando se requiera trazabilidad precisa por destinatario.

---

## Reglas sobre `lamportTimestamp`

Todo mensaje inter-nodo relevante debe incluir una marca lógica de Lamport.

Reglas esperadas:

1. Antes de enviar un mensaje inter-nodo:
   el nodo emisor incrementa su reloj de Lamport.

2. El valor actualizado se adjunta al mensaje.

3. Al recibir un mensaje inter-nodo:
   el nodo receptor actualiza su reloj local con:

~~~text
L_local = max(L_local, L_recibido) + 1
~~~

4. El evento de recepción se registra con el nuevo valor de Lamport.

Ejemplo de log:

~~~text
[node1][L=12] SEND PRIVATE_MESSAGE_FORWARD to node2
[node2][L=17] RECEIVE PRIVATE_MESSAGE_FORWARD from node1
~~~

---

## Uso permitido de `sentAtMillis`

El campo `sentAtMillis` solo puede usarse para métricas, por ejemplo:

- latencia;
- RTT;
- duración de entrega;
- tiempo de respuesta;
- comparación normal vs falla inducida.

No debe usarse para ordenar eventos distribuidos.

Ejemplo permitido:

~~~text
latencia = receivedAtMillis - sentAtMillis
~~~

Ejemplo no permitido:

~~~text
ordenar mensajes distribuidos por sentAtMillis
~~~

---

## Enumeración de tipos de mensaje

La enumeración base recomendada es:

~~~java
public enum NodeMessageType {
    PEER_HELLO,
    PEER_HELLO_ACK,
    MEMBERSHIP_UPDATE,

    USER_LOGIN_ANNOUNCE,
    USER_LOGOUT_ANNOUNCE,
    USER_LOCATION_QUERY,
    USER_LOCATION_RESPONSE,

    PRIVATE_MESSAGE_FORWARD,
    PRIVATE_MESSAGE_ACK,

    GROUP_MESSAGE_FORWARD,
    GROUP_MESSAGE_ACK,

    GROUP_CREATE_REQUEST,
    GROUP_JOIN_REQUEST,
    GROUP_LEAVE_REQUEST,
    GROUP_UPDATE_COMMIT,
    GROUP_UPDATE_ACK,

    MUTEX_REQUEST,
    MUTEX_REPLY,
    MUTEX_RELEASE,

    HEARTBEAT,
    HEARTBEAT_ACK,

    NODE_ERROR
}
~~~

---

## Tabla general de mensajes

| Tipo | Propósito | Emisor típico | Receptor típico | Procesado por |
|---|---|---|---|---|
| `PEER_HELLO` | Presentar un nodo al iniciar | Nodo nuevo | Peer conocido | `peerWorkerPool` |
| `PEER_HELLO_ACK` | Confirmar presentación | Peer conocido | Nodo nuevo | `peerWorkerPool` |
| `MEMBERSHIP_UPDATE` | Informar cambios de membresía | Cualquier nodo | Peers | `peerWorkerPool` |
| `USER_LOGIN_ANNOUNCE` | Anunciar usuario conectado | Nodo local del usuario | Peers | `peerWorkerPool` |
| `USER_LOGOUT_ANNOUNCE` | Anunciar usuario desconectado | Nodo local del usuario | Peers | `peerWorkerPool` |
| `USER_LOCATION_QUERY` | Consultar ubicación de usuario | Nodo solicitante | Peer | `peerWorkerPool` |
| `USER_LOCATION_RESPONSE` | Responder ubicación de usuario | Peer | Nodo solicitante | `peerWorkerPool` |
| `PRIVATE_MESSAGE_FORWARD` | Reenviar mensaje privado | Nodo origen | Nodo destino | `peerWorkerPool` |
| `PRIVATE_MESSAGE_ACK` | Confirmar recepción/entrega privada | Nodo destino | Nodo origen | `peerWorkerPool` |
| `GROUP_MESSAGE_FORWARD` | Reenviar mensaje grupal | Nodo origen | Nodos con miembros | `peerWorkerPool` |
| `GROUP_MESSAGE_ACK` | Confirmar recepción/entrega grupal | Nodo destino | Nodo origen | `peerWorkerPool` |
| `GROUP_CREATE_REQUEST` | Solicitar creación de grupo | Nodo solicitante | Coordinación | `peerWorkerPool` + `coordinationExecutor` |
| `GROUP_JOIN_REQUEST` | Solicitar unión a grupo | Nodo solicitante | Coordinación | `peerWorkerPool` + `coordinationExecutor` |
| `GROUP_LEAVE_REQUEST` | Solicitar salida de grupo | Nodo solicitante | Coordinación | `peerWorkerPool` + `coordinationExecutor` |
| `GROUP_UPDATE_COMMIT` | Replicar cambio ya autorizado | Nodo que ejecutó sección crítica | Peers | `peerWorkerPool` |
| `GROUP_UPDATE_ACK` | Confirmar actualización de grupo | Peer actualizado | Nodo emisor | `peerWorkerPool` |
| `MUTEX_REQUEST` | Solicitar sección crítica | Nodo solicitante | Peers | `peerWorkerPool` + `coordinationExecutor` |
| `MUTEX_REPLY` | Autorizar sección crítica | Peer | Nodo solicitante | `peerWorkerPool` + `coordinationExecutor` |
| `MUTEX_RELEASE` | Informar liberación de sección crítica | Nodo que sale | Peers | `peerWorkerPool` + `coordinationExecutor` |
| `HEARTBEAT` | Señal de vida | Scheduler de un nodo | Peers | `schedulerPool` / `peerWorkerPool` |
| `HEARTBEAT_ACK` | Confirmación de vida | Peer | Nodo emisor | `schedulerPool` / `peerWorkerPool` |
| `NODE_ERROR` | Informar error remoto | Cualquier nodo | Nodo afectado | `peerWorkerPool` |

---

## Mensajes de membresía

### PEER_HELLO

Mensaje usado cuando un nodo inicia y se presenta ante sus peers conocidos.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `nodeInfo` | `NodeInfo` | Información del nodo que se presenta |
| `knownPeers` | `List<NodeInfo>` | Lista de peers conocidos por el emisor |

#### Ejemplo conceptual

~~~java
class PeerHelloMessage extends NodeMessage {
    private NodeInfo nodeInfo;
    private List<NodeInfo> knownPeers;
}
~~~

#### Procesamiento esperado

1. El nodo receptor valida el `sourceNodeId`.
2. Registra o actualiza la información del nodo emisor.
3. Marca al nodo como `ALIVE`.
4. Responde con `PEER_HELLO_ACK`.

---

### PEER_HELLO_ACK

Mensaje de confirmación ante un `PEER_HELLO`.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `accepted` | `boolean` | Indica si el peer fue aceptado |
| `receiverNodeInfo` | `NodeInfo` | Información del nodo que responde |
| `knownPeers` | `List<NodeInfo>` | Peers conocidos por el receptor |

#### Procesamiento esperado

1. El nodo que recibe el ACK actualiza su lista de membresía.
2. Registra al peer como `ALIVE`.
3. Registra log de conexión exitosa.

Ejemplo de log:

~~~text
[node1][L=5] Peer detectado: node2
~~~

---

### MEMBERSHIP_UPDATE

Mensaje usado para informar cambios de estado en la membresía.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `nodes` | `List<NodeInfo>` | Estado conocido de los nodos |
| `reason` | `String` | Motivo del cambio |

#### Estados permitidos

~~~java
public enum NodeStatus {
    ALIVE,
    SUSPECTED,
    DOWN,
    RECOVERING
}
~~~

#### Ejemplo de uso

~~~text
node1 informa a node3 que node2 fue marcado como DOWN
~~~

---

## Mensajes de directorio de usuarios

### USER_LOGIN_ANNOUNCE

Mensaje usado cuando un usuario inicia sesión en un nodo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `userId` | `String` | Usuario conectado |
| `connectedNodeId` | `String` | Nodo donde quedó conectado |
| `loginEventId` | `String` | ID del evento de login |

#### Efecto esperado

Cada nodo receptor actualiza su `GlobalUserDirectory`:

~~~text
userId -> connectedNodeId
~~~

#### Ejemplo

~~~text
Usuario ana inicia sesión en node1.
node1 envía USER_LOGIN_ANNOUNCE(ana, node1) a node2 y node3.
~~~

---

### USER_LOGOUT_ANNOUNCE

Mensaje usado cuando un usuario se desconecta de un nodo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `userId` | `String` | Usuario desconectado |
| `previousNodeId` | `String` | Nodo donde estaba conectado |
| `reason` | `String` | Motivo de desconexión |

#### Efecto esperado

Cada nodo receptor elimina o invalida la entrada:

~~~text
userId -> previousNodeId
~~~

---

### USER_LOCATION_QUERY

Mensaje usado cuando un nodo no sabe dónde se encuentra un usuario.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `requestedUserId` | `String` | Usuario buscado |
| `requestId` | `String` | ID de correlación |

#### Uso esperado

Este mensaje es opcional si todos los nodos mantienen un `GlobalUserDirectory` replicado. Puede usarse como mecanismo de respaldo si el directorio local está incompleto.

---

### USER_LOCATION_RESPONSE

Respuesta a `USER_LOCATION_QUERY`.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `requestedUserId` | `String` | Usuario consultado |
| `found` | `boolean` | Indica si se encontró |
| `locatedNodeId` | `String` | Nodo donde está el usuario |
| `requestId` | `String` | ID de correlación |

---

## Mensajes privados distribuidos

### PRIVATE_MESSAGE_FORWARD

Mensaje usado para reenviar un mensaje privado desde el nodo del emisor hacia el nodo del destinatario.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `fromUserId` | `String` | Usuario emisor |
| `toUserId` | `String` | Usuario destinatario |
| `content` | `String` | Contenido del mensaje |
| `clientMessageId` | `String` | ID del mensaje original del cliente |
| `requiresAck` | `boolean` | Indica si se espera confirmación |

#### Flujo esperado

1. Cliente A conectado a `node1` envía mensaje a Cliente B.
2. `node1` consulta `GlobalUserDirectory`.
3. `node1` determina que Cliente B está en `node2`.
4. `node1` envía `PRIVATE_MESSAGE_FORWARD` a `node2`.
5. `node2` entrega el mensaje a Cliente B.
6. `node2` responde `PRIVATE_MESSAGE_ACK`.

#### Ejemplo de log

~~~text
[node1][L=11] SEND PRIVATE_MESSAGE_FORWARD usuarioA -> usuarioB target=node2
[node2][L=15] RECEIVE PRIVATE_MESSAGE_FORWARD usuarioA -> usuarioB
[node2][L=16] DELIVER_LOCAL usuarioB
~~~

---

### PRIVATE_MESSAGE_ACK

Mensaje usado para confirmar el resultado de un mensaje privado remoto.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `originalMessageId` | `String` | `messageId` del `PRIVATE_MESSAGE_FORWARD` |
| `clientMessageId` | `String` | ID del mensaje original del cliente |
| `delivered` | `boolean` | Indica si fue entregado |
| `errorCode` | `String` | Código de error, si aplica |
| `detail` | `String` | Detalle opcional |

#### Códigos de error posibles

| Código | Significado |
|---|---|
| `USER_NOT_LOCAL` | El usuario no está conectado en el nodo receptor |
| `USER_DISCONNECTED` | El usuario se desconectó antes de la entrega |
| `DELIVERY_FAILED` | Error al escribir al socket del cliente |
| `DUPLICATE_MESSAGE` | Mensaje ya procesado |
| `NODE_SHUTTING_DOWN` | Nodo receptor está cerrando |

---

## Mensajes grupales distribuidos

### GROUP_MESSAGE_FORWARD

Mensaje usado para reenviar un mensaje grupal a nodos que poseen miembros locales del grupo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `groupId` | `String` | Grupo destino |
| `fromUserId` | `String` | Usuario emisor |
| `content` | `String` | Contenido del mensaje |
| `targetLocalMembers` | `List<String>` | Miembros que deberían recibirlo en el nodo destino |
| `clientMessageId` | `String` | ID del mensaje original del cliente |
| `requiresAck` | `boolean` | Indica si se espera confirmación |

#### Flujo esperado

1. Cliente A envía mensaje a `grupoX` en `node1`.
2. `node1` valida grupo y membresía.
3. `node1` entrega a miembros locales.
4. `node1` identifica miembros remotos por nodo.
5. `node1` envía `GROUP_MESSAGE_FORWARD` a cada nodo con miembros remotos.
6. Cada nodo receptor entrega a sus clientes locales.
7. Cada nodo receptor responde `GROUP_MESSAGE_ACK`.

---

### GROUP_MESSAGE_ACK

Mensaje usado para confirmar resultado de entrega grupal remota.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `originalMessageId` | `String` | `messageId` del `GROUP_MESSAGE_FORWARD` |
| `groupId` | `String` | Grupo relacionado |
| `deliveredUsers` | `List<String>` | Usuarios que recibieron el mensaje |
| `failedUsers` | `List<String>` | Usuarios a los que no se pudo entregar |
| `errorCode` | `String` | Código de error general, si aplica |

#### Códigos de error posibles

| Código | Significado |
|---|---|
| `GROUP_NOT_FOUND` | El grupo no existe en el nodo receptor |
| `NO_LOCAL_MEMBERS` | El nodo no tiene miembros locales para ese grupo |
| `PARTIAL_DELIVERY` | Algunos usuarios recibieron y otros no |
| `DELIVERY_FAILED` | Falló la entrega local |
| `DUPLICATE_MESSAGE` | Mensaje ya procesado |

---

## Mensajes de actualización de grupos

Las operaciones sobre grupos se consideran críticas porque afectan el recurso distribuido:

~~~text
GROUP_REGISTRY
~~~

Estas operaciones deberán coordinarse con Lamport + Ricart-Agrawala antes de confirmarse.

---

### GROUP_CREATE_REQUEST

Mensaje conceptual que representa la intención de crear un grupo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `groupId` | `String` | Identificador del grupo |
| `creatorUserId` | `String` | Usuario creador |
| `initialMembers` | `List<String>` | Miembros iniciales |
| `operationId` | `String` | ID de operación distribuida |

#### Regla

Este mensaje no debe modificar directamente el estado replicado si no se ha entrado a sección crítica.

---

### GROUP_JOIN_REQUEST

Mensaje conceptual que representa la intención de unir un usuario a un grupo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `groupId` | `String` | Grupo objetivo |
| `userId` | `String` | Usuario que se une |
| `requestedByUserId` | `String` | Usuario que solicitó la operación |
| `operationId` | `String` | ID de operación distribuida |

---

### GROUP_LEAVE_REQUEST

Mensaje conceptual que representa la intención de sacar o desconectar un usuario de un grupo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `groupId` | `String` | Grupo objetivo |
| `userId` | `String` | Usuario que sale |
| `requestedByUserId` | `String` | Usuario que solicitó la operación |
| `operationId` | `String` | ID de operación distribuida |

---

### GROUP_UPDATE_COMMIT

Mensaje usado para replicar un cambio de grupo una vez que el nodo obtuvo acceso a la sección crítica.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `operationId` | `String` | ID de la operación confirmada |
| `groupId` | `String` | Grupo afectado |
| `operationType` | `String` | `CREATE`, `JOIN`, `LEAVE`, `UPDATE` |
| `membersSnapshot` | `List<String>` | Estado resultante de membresía |
| `committedByNodeId` | `String` | Nodo que ejecutó la sección crítica |
| `commitLamport` | `long` | Timestamp lógico del commit |

#### Efecto esperado

Cada nodo receptor actualiza su `DistributedGroupManager`.

---

### GROUP_UPDATE_ACK

Mensaje usado para confirmar que un nodo aplicó una actualización de grupo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `operationId` | `String` | ID de operación aplicada |
| `groupId` | `String` | Grupo afectado |
| `applied` | `boolean` | Indica si se aplicó correctamente |
| `errorCode` | `String` | Código de error, si aplica |

---

## Mensajes de coordinación distribuida

La coordinación distribuida se realiza para proteger:

~~~text
GROUP_REGISTRY
~~~

Se recomienda usar Ricart-Agrawala con relojes de Lamport.

---

### MUTEX_REQUEST

Mensaje usado para solicitar entrada a sección crítica.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `resourceId` | `String` | Recurso crítico solicitado |
| `requestTimestamp` | `long` | Timestamp de Lamport de la solicitud |
| `requestingNodeId` | `String` | Nodo que solicita entrar |
| `operationId` | `String` | Operación asociada |

#### Ejemplo

~~~text
[node1][L=21] MUTEX_REQUEST GROUP_REGISTRY
~~~

#### Regla de comparación

Para decidir prioridad se compara:

~~~text
(requestTimestamp, requestingNodeId)
~~~

Tiene prioridad el menor timestamp. Si hay empate, gana el menor `nodeId`.

---

### MUTEX_REPLY

Mensaje usado para autorizar entrada a sección crítica.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `resourceId` | `String` | Recurso solicitado |
| `grantedToNodeId` | `String` | Nodo autorizado |
| `replyToOperationId` | `String` | Operación asociada |
| `deferred` | `boolean` | Indica si fue respuesta diferida |

#### Ejemplo

~~~text
[node2][L=25] MUTEX_REPLY to node1 resource=GROUP_REGISTRY
~~~

---

### MUTEX_RELEASE

Mensaje opcional para informar salida de sección crítica.

Ricart-Agrawala clásico puede trabajar respondiendo solicitudes diferidas al salir de la sección crítica, pero este mensaje puede usarse para trazabilidad y logs.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `resourceId` | `String` | Recurso liberado |
| `releasedByNodeId` | `String` | Nodo que libera |
| `operationId` | `String` | Operación completada |

---

## Mensajes de fallos y heartbeats

### HEARTBEAT

Mensaje periódico usado para indicar que un nodo sigue activo.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `status` | `NodeStatus` | Estado actual del emisor |
| `knownTerm` | `long` | Época o contador local opcional |
| `knownAliveNodes` | `List<String>` | Nodos que el emisor considera vivos |

#### Procesamiento esperado

1. El receptor actualiza `lastHeartbeatReceived`.
2. Marca al emisor como `ALIVE` si corresponde.
3. Responde `HEARTBEAT_ACK`.

---

### HEARTBEAT_ACK

Confirmación de heartbeat.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `heartbeatMessageId` | `String` | ID del heartbeat respondido |
| `status` | `NodeStatus` | Estado del nodo que responde |

---

## Mensaje de error

### NODE_ERROR

Mensaje genérico para reportar errores entre nodos.

#### Campos específicos

| Campo | Tipo | Descripción |
|---|---|---|
| `originalMessageId` | `String` | Mensaje que produjo el error |
| `errorCode` | `String` | Código de error |
| `detail` | `String` | Descripción breve |
| `recoverable` | `boolean` | Indica si se puede reintentar |

#### Códigos de error generales

| Código | Significado |
|---|---|
| `UNKNOWN_MESSAGE_TYPE` | Tipo de mensaje no reconocido |
| `INVALID_SOURCE_NODE` | Nodo origen inválido |
| `INVALID_TARGET_NODE` | Nodo destino inválido |
| `NODE_DOWN` | Nodo requerido está caído |
| `NODE_SUSPECTED` | Nodo requerido está sospechoso |
| `USER_NOT_FOUND` | Usuario no encontrado |
| `GROUP_NOT_FOUND` | Grupo no encontrado |
| `NOT_GROUP_MEMBER` | Usuario no pertenece al grupo |
| `DUPLICATE_MESSAGE` | Mensaje duplicado |
| `LAMPORT_ERROR` | Timestamp lógico inválido |
| `SERIALIZATION_ERROR` | Error de serialización |
| `DELIVERY_FAILED` | Fallo de entrega |
| `TIMEOUT` | Tiempo de espera agotado |
| `INTERNAL_ERROR` | Error interno del nodo |

---

## Contrato de procesamiento por pool

| Tipo de mensaje | Pool receptor | Componente principal |
|---|---|---|
| `PEER_HELLO` | `peerWorkerPool` | `MembershipManager` |
| `PEER_HELLO_ACK` | `peerWorkerPool` | `MembershipManager` |
| `MEMBERSHIP_UPDATE` | `peerWorkerPool` | `MembershipManager` |
| `USER_LOGIN_ANNOUNCE` | `peerWorkerPool` | `GlobalUserDirectory` |
| `USER_LOGOUT_ANNOUNCE` | `peerWorkerPool` | `GlobalUserDirectory` |
| `USER_LOCATION_QUERY` | `peerWorkerPool` | `GlobalUserDirectory` |
| `USER_LOCATION_RESPONSE` | `peerWorkerPool` | `GlobalUserDirectory` |
| `PRIVATE_MESSAGE_FORWARD` | `peerWorkerPool` | `MessageRouter` / `LocalSessionManager` |
| `PRIVATE_MESSAGE_ACK` | `peerWorkerPool` | `MessageRouter` / `MetricsCollector` |
| `GROUP_MESSAGE_FORWARD` | `peerWorkerPool` | `MessageRouter` / `DistributedGroupManager` |
| `GROUP_MESSAGE_ACK` | `peerWorkerPool` | `MetricsCollector` |
| `GROUP_CREATE_REQUEST` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `GROUP_JOIN_REQUEST` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `GROUP_LEAVE_REQUEST` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `GROUP_UPDATE_COMMIT` | `peerWorkerPool` | `DistributedGroupManager` |
| `GROUP_UPDATE_ACK` | `peerWorkerPool` | `MetricsCollector` |
| `MUTEX_REQUEST` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `MUTEX_REPLY` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `MUTEX_RELEASE` | `peerWorkerPool` + `coordinationExecutor` | `MutualExclusionManager` |
| `HEARTBEAT` | `peerWorkerPool` | `HeartbeatManager` |
| `HEARTBEAT_ACK` | `peerWorkerPool` | `HeartbeatManager` |
| `NODE_ERROR` | `peerWorkerPool` | Componente relacionado |

---

## Reglas de validación al recibir un mensaje

Al recibir cualquier `NodeMessage`, el nodo receptor debe validar:

1. Que `messageId` no sea nulo.
2. Que `sourceNodeId` exista en la membresía o sea aceptable durante `PEER_HELLO`.
3. Que `targetNodeId` corresponda al nodo receptor o sea `*`.
4. Que `type` sea reconocido.
5. Que `lamportTimestamp` sea válido.
6. Que el mensaje sea compatible con el estado actual del nodo.
7. Que no sea duplicado, si ya existe registro de `messageId`.
8. Que los campos específicos del tipo no sean nulos si son obligatorios.

Si falla una validación, se debe registrar el evento y responder `NODE_ERROR` cuando corresponda.

---

## Reglas de deduplicación

Cada nodo debe mantener, al menos durante la ejecución, un registro de mensajes procesados:

~~~text
processedMessageIds
~~~

Uso:

- evitar doble entrega de mensajes privados;
- evitar doble entrega de mensajes grupales;
- evitar aplicar dos veces un `GROUP_UPDATE_COMMIT`;
- detectar reintentos;
- mejorar trazabilidad de fallos.

Si llega un mensaje duplicado, el nodo no debe aplicar nuevamente efectos secundarios.

Puede responder un ACK indicando:

~~~text
DUPLICATE_MESSAGE
~~~

---

## Reglas de ACK

Los ACKs no son obligatorios para todos los mensajes, pero sí se recomiendan para:

- `PRIVATE_MESSAGE_FORWARD`;
- `GROUP_MESSAGE_FORWARD`;
- `GROUP_UPDATE_COMMIT`;
- `HEARTBEAT`;
- mensajes críticos de coordinación, cuando aplique.

Los ACKs permiten:

- medir latencia;
- detectar omisiones;
- calcular tasa de error;
- generar logs de prueba;
- facilitar reintentos controlados.

---

## Reglas de reintento

El sistema puede reintentar mensajes no críticos, pero debe evitar reintentar indefinidamente.

Recomendación:

~~~properties
message.retry.maxAttempts=3
message.retry.delayMs=500
~~~

Mensajes que pueden reintentarse:

- `PRIVATE_MESSAGE_FORWARD`;
- `GROUP_MESSAGE_FORWARD`;
- `GROUP_UPDATE_COMMIT`;
- `HEARTBEAT`.

Mensajes que deben tratarse con cuidado:

- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- operaciones de grupo ya confirmadas.

Toda operación reintentada debe mantener el mismo `messageId` o un `correlationId` común para evitar duplicados.

---

## Reglas de logs

Todo mensaje inter-nodo relevante debe registrar logs con este formato recomendado:

~~~text
[nodeId][L=lamport] EVENT type=TYPE messageId=ID source=SRC target=DST detail=...
~~~

Ejemplos:

~~~text
[node1][L=10] SEND type=USER_LOGIN_ANNOUNCE messageId=node1-1 source=node1 target=node2 user=ana
[node2][L=14] RECEIVE type=USER_LOGIN_ANNOUNCE messageId=node1-1 source=node1 target=node2 user=ana
[node1][L=21] SEND type=MUTEX_REQUEST resource=GROUP_REGISTRY target=node2
[node2][L=25] SEND type=MUTEX_REPLY grantedTo=node1
[node1][L=30] ENTER_CRITICAL_SECTION resource=GROUP_REGISTRY
~~~

---

## Relación con funciones principales

### Mensajería privada distribuida

Mensajes involucrados:

- `USER_LOGIN_ANNOUNCE`;
- `USER_LOGOUT_ANNOUNCE`;
- `USER_LOCATION_QUERY`, si aplica;
- `USER_LOCATION_RESPONSE`, si aplica;
- `PRIVATE_MESSAGE_FORWARD`;
- `PRIVATE_MESSAGE_ACK`;
- `NODE_ERROR`.

---

### Mensajería grupal distribuida

Mensajes involucrados:

- `GROUP_MESSAGE_FORWARD`;
- `GROUP_MESSAGE_ACK`;
- `GROUP_UPDATE_COMMIT`;
- `GROUP_UPDATE_ACK`;
- `NODE_ERROR`.

---

### Creación o modificación de grupos

Mensajes involucrados:

- `GROUP_CREATE_REQUEST`;
- `GROUP_JOIN_REQUEST`;
- `GROUP_LEAVE_REQUEST`;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- `MUTEX_RELEASE`, si se implementa;
- `GROUP_UPDATE_COMMIT`;
- `GROUP_UPDATE_ACK`;
- `NODE_ERROR`.

---

### Detección de fallos

Mensajes involucrados:

- `HEARTBEAT`;
- `HEARTBEAT_ACK`;
- `MEMBERSHIP_UPDATE`;
- `NODE_ERROR`.

---

## Ejemplo de flujo: mensaje privado remoto

~~~text
Cliente A conectado a node1 quiere enviar mensaje a Cliente B conectado a node2.

1. node1 recibe PaqueteMensaje desde Cliente A.
2. node1 incrementa Lamport.
3. node1 consulta GlobalUserDirectory.
4. node1 determina que Cliente B está en node2.
5. node1 envía PRIVATE_MESSAGE_FORWARD a node2.
6. node2 actualiza Lamport al recibir.
7. node2 busca a Cliente B en LocalSessionManager.
8. node2 entrega el mensaje a Cliente B.
9. node2 responde PRIVATE_MESSAGE_ACK a node1.
10. node1 registra resultado y métricas.
~~~

---

## Ejemplo de flujo: mensaje grupal remoto

~~~text
Cliente A conectado a node1 envía mensaje al grupo grupoX.

1. node1 recibe mensaje grupal.
2. node1 valida que Cliente A pertenezca a grupoX.
3. node1 obtiene miembros de grupoX.
4. node1 separa miembros locales y remotos.
5. node1 entrega a miembros locales.
6. node1 envía GROUP_MESSAGE_FORWARD a node2 si tiene miembros remotos.
7. node1 envía GROUP_MESSAGE_FORWARD a node3 si tiene miembros remotos.
8. node2 y node3 entregan a sus miembros locales.
9. node2 y node3 responden GROUP_MESSAGE_ACK.
10. node1 registra métricas.
~~~

---

## Ejemplo de flujo: creación de grupo coordinada

~~~text
Cliente A conectado a node1 solicita crear grupoX.

1. node1 recibe solicitud de creación de grupo.
2. node1 incrementa Lamport.
3. node1 envía MUTEX_REQUEST(GROUP_REGISTRY) a node2 y node3.
4. node2 y node3 responden MUTEX_REPLY si corresponde.
5. node1 espera respuestas necesarias.
6. node1 entra a sección crítica.
7. node1 crea grupoX en su DistributedGroupManager.
8. node1 envía GROUP_UPDATE_COMMIT a node2 y node3.
9. node2 y node3 aplican la actualización.
10. node2 y node3 responden GROUP_UPDATE_ACK.
11. node1 sale de sección crítica.
12. node1 responde al cliente.
~~~

---

## Ejemplo de flujo: heartbeat y caída de nodo

~~~text
1. node1 envía HEARTBEAT a node2.
2. node2 responde HEARTBEAT_ACK.
3. node1 actualiza lastSeen(node2).

Si node2 deja de responder:

4. node1 no recibe HEARTBEAT_ACK dentro del timeout.
5. node1 marca node2 como SUSPECTED.
6. Si se supera el umbral configurado, node1 marca node2 como DOWN.
7. node1 emite MEMBERSHIP_UPDATE hacia los nodos disponibles.
8. node1 evita enrutar nuevos mensajes hacia node2.
~~~

---

## Criterios de aceptación del contrato

Este contrato se considera correctamente aplicado cuando:

1. Existe una clase base o equivalente para `NodeMessage`.
2. Todo mensaje inter-nodo incluye `messageId`, `sourceNodeId`, `targetNodeId`, `type`, `lamportTimestamp` y `sentAtMillis`.
3. Existen tipos de mensaje diferenciados para membresía, usuarios, mensajes privados, grupos, coordinación, heartbeats y errores.
4. Los mensajes inter-nodo se procesan en `peerWorkerPool`, no en `clientWorkerPool`.
5. Los mensajes de coordinación se derivan a `coordinationExecutor`.
6. Los heartbeats no dependen del pool de clientes.
7. Los mensajes privados remotos usan `PRIVATE_MESSAGE_FORWARD`.
8. Los mensajes grupales remotos usan `GROUP_MESSAGE_FORWARD`.
9. Las actualizaciones de grupo usan `GROUP_UPDATE_COMMIT`.
10. La coordinación sobre `GROUP_REGISTRY` usa `MUTEX_REQUEST` y `MUTEX_REPLY`.
11. Los eventos relevantes se registran con Lamport.
12. Los errores remotos se manejan mediante `NODE_ERROR` o logs controlados.
13. Los mensajes duplicados pueden detectarse mediante `messageId`.
14. La prueba de carga puede contabilizar mensajes, ACKs, errores y latencias.

---

## Conclusión

El contrato de mensajes entre nodos define la base de comunicación de la arquitectura multiservidor.

Mientras la Entrega Inicial solo requería mensajes entre clientes y un servidor central, la Entrega Final necesita mensajes servidor-servidor para sostener:

- enrutamiento distribuido;
- directorio de usuarios;
- grupos distribuidos;
- coordinación sobre recursos críticos;
- detección de fallos;
- métricas de carga;
- recuperación parcial.

Este contrato permite que los distintos integrantes del equipo implementen sus módulos de forma coherente, evitando que cada persona invente formatos de mensajes incompatibles.