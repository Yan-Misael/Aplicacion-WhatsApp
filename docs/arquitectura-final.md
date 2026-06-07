# Arquitectura final del sistema

## Propósito del documento

Este documento define la arquitectura final del sistema distribuido de mensajería instantánea inspirado en WhatsApp.

La Entrega Inicial implementaba una arquitectura Cliente-Servidor Broker centralizada. La Entrega Final evoluciona esa base hacia una arquitectura multiservidor, compuesta por varios `ServerNode` independientes que aceptan clientes locales y se comunican entre sí mediante sockets TCP.

Este documento sirve como guía arquitectónica para el resto del equipo. Debe permitir que las personas encargadas de comunicación entre nodos, funciones distribuidas, relojes lógicos, coordinación, fallos, métricas e informe trabajen sobre una misma base técnica.

---

## Objetivo arquitectónico

El objetivo de la arquitectura final es transformar el sistema desde:

~~~text
Un único servidor central que administra todo
~~~

hacia:

~~~text
Tres o más nodos servidores independientes que colaboran mediante mensajes
~~~

La arquitectura final debe permitir demostrar:

- comunicación real entre procesos remotos;
- concurrencia de clientes y nodos;
- ausencia de reloj global;
- uso de relojes lógicos;
- coordinación distribuida;
- tolerancia parcial a fallos;
- continuidad de servicio ante caída de un nodo;
- prueba de carga con métricas;
- transparencia de ubicación para los clientes;
- separación clara entre estado local y estado distribuido.

---

## Decisiones arquitectónicas asociadas

Esta arquitectura se apoya en dos decisiones formales:

| ADR | Decisión | Resumen |
|---|---|---|
| ADR-001 | Evolución a arquitectura multiservidor | Se reemplaza el broker centralizado por tres `ServerNode` cooperativos |
| ADR-002 | Uso de thread-pools acotados por nodo | Se reemplaza Thread-per-Connection puro por pools separados por responsabilidad |

---

## Arquitectura general

La arquitectura final se define como una arquitectura híbrida multiservidor.

El sistema conserva el modelo cliente-servidor en el borde, porque los clientes siguen conectándose a un nodo servidor. Sin embargo, la capa servidora deja de estar centralizada y pasa a estar compuesta por múltiples nodos que colaboran entre sí.

~~~text
Cliente -> ServerNode local
ServerNode <-> ServerNode
~~~

Cada `ServerNode` cumple dos roles:

1. Servidor de clientes locales.
2. Peer distribuido que se comunica con otros `ServerNode`.

---

## Modelo físico final

El modelo físico mínimo contempla tres nodos servidores y múltiples clientes.

~~~text
                    Red TCP/IP / LAN

       Cliente A ──────────────> node1
                                  │
                                  │
                                  │
       Cliente B ──────────────> node2
                                  │
                                  │
                                  │
       Cliente C ──────────────> node3


       node1 <───────────────> node2
       node1 <───────────────> node3
       node2 <───────────────> node3
~~~

Cada cliente se conecta a un nodo específico. Los nodos se comunican entre sí para reenviar mensajes, mantener membresía, actualizar directorios, detectar fallos y coordinar recursos compartidos.

---

## Nodos mínimos del sistema

| Nodo lógico | Host de demo | Puerto clientes | Puerto inter-nodo |
|---|---|---:|---:|
| `node1` | `localhost` | `5001` | `6001` |
| `node2` | `localhost` | `5002` | `6002` |
| `node3` | `localhost` | `5003` | `6003` |

---

## Ejecución esperada de nodos

La base técnica entregada por Persona 1 inicia cada nodo leyendo un archivo `.properties`.

Comandos esperados:

~~~bash
java whatsapp.server.ServerNode config/node1.properties
java whatsapp.server.ServerNode config/node2.properties
java whatsapp.server.ServerNode config/node3.properties
~~~

En Maven:

~~~bash
mvn -Dexec.mainClass=whatsapp.server.ServerNode -Dexec.args="config/node1.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
mvn -Dexec.mainClass=whatsapp.server.ServerNode -Dexec.args="config/node2.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
mvn -Dexec.mainClass=whatsapp.server.ServerNode -Dexec.args="config/node3.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
~~~

Persona 2 puede extender el `main` para aceptar también argumentos explícitos como:

~~~text
nodeId clientPort peerPort configPath
~~~

pero no debe romper el modo actual basado en archivo de configuración.

---

## Ejecución esperada de clientes

Los clientes deben poder conectarse a cualquier nodo:

~~~bash
java whatsapp.client.ClienteNodo localhost 5001
java whatsapp.client.ClienteNodo localhost 5002
java whatsapp.client.ClienteNodo localhost 5003
~~~

La adaptación completa del cliente y de los comandos de usuario pertenece principalmente a Persona 3.

---

## Configuración por nodo

Cada nodo debe contar con un archivo `.properties`.

Estructura esperada:

~~~text
config/
├── node1.properties
├── node2.properties
└── node3.properties
~~~

### Formato de peers

El formato oficial de `node.peers` será:

~~~text
nodeId@host:clientPort:peerPort
~~~

Ejemplo:

~~~text
node2@localhost:5002:6002
~~~

Se incluye `clientPort` y `peerPort` porque `NodeInfo` representa ambos puertos.

---

### Ejemplo: `config/node1.properties`

~~~properties
node.id=node1
node.host=localhost
node.clientPort=5001
node.peerPort=6001

node.peers=node2@localhost:5002:6002,node3@localhost:5003:6003

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000

message.retry.maxAttempts=3
message.retry.delayMs=500
~~~

---

### Ejemplo: `config/node2.properties`

~~~properties
node.id=node2
node.host=localhost
node.clientPort=5002
node.peerPort=6002

node.peers=node1@localhost:5001:6001,node3@localhost:5003:6003

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000

message.retry.maxAttempts=3
message.retry.delayMs=500
~~~

---

### Ejemplo: `config/node3.properties`

~~~properties
node.id=node3
node.host=localhost
node.clientPort=5003
node.peerPort=6003

node.peers=node1@localhost:5001:6001,node2@localhost:5002:6002

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000

message.retry.maxAttempts=3
message.retry.delayMs=500
~~~

---

## Identidad de nodos

Cada nodo debe tener una identidad lógica estable:

~~~text
node1
node2
node3
~~~

La identidad principal del nodo no debe ser su IP ni su puerto. La IP y los puertos pertenecen a su configuración física, pero el identificador lógico permite mantener trazabilidad, logs, membresía y ordenamiento distribuido de forma más clara.

Ejemplo:

~~~text
NodeInfo {
    nodeId = "node1"
    host = "localhost"
    clientPort = 5001
    peerPort = 6001
    status = ALIVE
}
~~~

---

## Componentes principales de cada ServerNode

Cada `ServerNode` debe organizarse internamente en componentes separados.

~~~text
ServerNode
 ├── ClientAcceptor
 ├── clientWorkerPool
 ├── ClientConnectionHandler
 ├── PeerListener
 ├── peerWorkerPool
 ├── PeerMessageHandler
 ├── schedulerPool
 ├── HeartbeatManager
 ├── FailureDetector
 ├── coordinationExecutor
 ├── MutualExclusionManager
 ├── MessageRouter
 ├── LocalSessionManager
 ├── GlobalUserDirectory
 ├── DistributedGroupManager
 ├── MembershipManager
 ├── LamportClock
 └── MetricsCollector
~~~

---

## Base técnica entregada por Persona 1

Persona 1 deja una base técnica inicial que inicializa la arquitectura multiservidor, pero todavía no implementa comunicación TCP real entre nodos.

La base técnica incluye:

- `ServerNode`;
- `ServerNodeContext`;
- `NodeConfig`;
- `NodeInfo`;
- `NodeStatus`;
- `NodeMessage`;
- `NodeMessageType`;
- `PeerHelloMessage`;
- `PeerHelloAckMessage`;
- `MembershipManager` base;
- `GlobalUserDirectory` base;
- `LocalSessionManager` base;
- `DistributedGroupManager` base;
- `MessageRouter` base;
- `PeerTransport`;
- `NoOpPeerTransport`;
- configuración `node1.properties`, `node2.properties`, `node3.properties`;
- thread-pools inicializados por nodo.

La clase clave para continuar es:

~~~java
PeerTransport
~~~

Actualmente existe una implementación temporal:

~~~java
NoOpPeerTransport
~~~

Esta implementación solo imprime logs y no envía mensajes por red.

Persona 2 debe reemplazar `NoOpPeerTransport` por una implementación TCP real.

Se recomienda crear:

- `TcpPeerTransport`;
- `PeerListener`;
- `PeerConnectionManager`;
- `PeerMessageHandler`.

El objetivo es que `ServerNode` deje de usar:

~~~java
new NoOpPeerTransport(config.getNodeId())
~~~

y pase a usar una implementación TCP real.

---

## Responsabilidades por componente

| Componente | Responsabilidad |
|---|---|
| `ServerNode` | Proceso principal del nodo servidor |
| `ServerNodeContext` | Contenedor de dependencias internas del nodo |
| `NodeConfig` | Cargar configuración del nodo desde archivo o argumentos |
| `NodeInfo` | Representar identidad, host, puertos y estado de un nodo |
| `ClientAcceptor` | Escuchar conexiones de clientes en `clientPort` |
| `ClientConnectionHandler` | Procesar mensajes provenientes de clientes locales |
| `PeerTransport` | Contrato abstracto para transporte inter-nodo |
| `NoOpPeerTransport` | Placeholder sin red real entregado por Persona 1 |
| `TcpPeerTransport` | Implementación TCP real esperada de Persona 2 |
| `PeerListener` | Escuchar mensajes o conexiones de otros nodos en `peerPort` |
| `PeerConnectionManager` | Enviar mensajes hacia otros `ServerNode` |
| `PeerMessageHandler` | Procesar mensajes recibidos desde nodos remotos |
| `MembershipManager` | Mantener lista de nodos conocidos y su estado |
| `LocalSessionManager` | Mantener sesiones de usuarios conectados localmente |
| `GlobalUserDirectory` | Resolver en qué nodo se encuentra conectado cada usuario |
| `DistributedGroupManager` | Mantener grupos y membresías distribuidas |
| `MessageRouter` | Decidir entrega local o reenvío remoto |
| `LamportClock` | Mantener reloj lógico local |
| `MutualExclusionManager` | Coordinar acceso a recurso crítico distribuido |
| `HeartbeatManager` | Enviar y recibir señales de vida |
| `FailureDetector` | Detectar nodos sospechosos o caídos |
| `MetricsCollector` | Registrar latencias, throughput, errores y coordinación |

---

## Modelo de concurrencia por nodo

Cada `ServerNode` debe usar thread-pools acotados y separados por responsabilidad.

No se debe usar un modelo Thread-per-Connection ilimitado para la arquitectura final.

### Pools definidos

| Pool | Tipo Java sugerido | Responsabilidad |
|---|---|---|
| `clientWorkerPool` | `ExecutorService` | Atención de clientes locales |
| `peerWorkerPool` | `ExecutorService` | Procesamiento de mensajes entre nodos |
| `schedulerPool` | `ScheduledExecutorService` | Heartbeats, timeouts y métricas periódicas |
| `coordinationExecutor` | `ExecutorService` o `SingleThreadExecutor` | Coordinación distribuida |

---

## Justificación del modelo de thread-pools

La Entrega Inicial podía usar Thread-per-Connection porque existía un solo servidor y el objetivo era demostrar concurrencia básica de clientes.

En la Entrega Final, cada nodo debe atender más responsabilidades:

- clientes locales;
- mensajes remotos;
- heartbeats;
- timeouts;
- coordinación distribuida;
- métricas;
- recuperación parcial.

Si todas esas tareas compartieran hilos sin control, una sobrecarga de clientes podría bloquear tareas críticas como heartbeats o respuestas de coordinación.

Por eso se separan los pools.

---

## Configuración recomendada de pools

| Parámetro | Valor sugerido | Justificación |
|---|---:|---|
| `pool.clients` | `64` | Soporta 50 clientes concurrentes con margen |
| `pool.peers` | `16` | Permite procesar mensajes inter-nodo sin depender de clientes |
| `pool.scheduler` | `4` | Permite ejecutar heartbeats, timeouts y métricas |
| `pool.coordination` | `1` | Simplifica el orden interno de coordinación |

---

## Reglas de uso de pools

### clientWorkerPool

Debe procesar:

- login de cliente;
- logout;
- mensaje privado iniciado por cliente;
- mensaje grupal iniciado por cliente;
- creación de grupo solicitada por cliente;
- unión a grupo solicitada por cliente;
- lectura de comandos de clientes.

No debe procesar:

- heartbeats;
- timeouts;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- mensajes inter-nodo;
- tareas periódicas.

---

### peerWorkerPool

Debe procesar:

- `PEER_HELLO`;
- `PEER_HELLO_ACK`;
- `MEMBERSHIP_UPDATE`;
- `USER_LOGIN_ANNOUNCE`;
- `USER_LOGOUT_ANNOUNCE`;
- `PRIVATE_MESSAGE_FORWARD`;
- `GROUP_MESSAGE_FORWARD`;
- `GROUP_UPDATE_COMMIT`;
- `HEARTBEAT`;
- `HEARTBEAT_ACK`;
- `NODE_ERROR`.

Debe derivar a `coordinationExecutor`:

- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- `MUTEX_RELEASE`;
- eventos asociados a Ricart-Agrawala.

---

### schedulerPool

Debe ejecutar:

- envío periódico de heartbeats;
- revisión de timeouts;
- detección de nodos caídos;
- recolección periódica de métricas;
- limpieza de estado expirado, si corresponde.

No debe depender del `clientWorkerPool`.

---

### coordinationExecutor

Debe ejecutar:

- procesamiento de solicitudes de exclusión mutua;
- comparación de timestamps de Lamport;
- gestión de cola de solicitudes diferidas;
- entrada a sección crítica;
- salida de sección crítica;
- envío de respuestas diferidas.

Se recomienda que sea de un solo hilo para simplificar el orden interno de coordinación dentro del nodo.

---

## Estado mantenido por cada ServerNode

| Estado | Tipo | Ubicación | Consistencia esperada |
|---|---|---|---|
| Sesiones locales | Local | Solo en el nodo donde está conectado el cliente | Fuerte local |
| Directorio usuario-nodo | Distribuido/replicado | Todos los nodos | Eventual |
| Grupos y membresías | Distribuido/replicado | Todos los nodos | Coordinada |
| Membresía de nodos | Distribuido | Todos los nodos | Eventual |
| Solicitudes de exclusión mutua | Local por nodo | Cada `ServerNode` | Ordenada por Lamport |
| Reloj de Lamport | Local por nodo | Cada `ServerNode` | Actualizado por evento |
| Mensajes procesados | Local por nodo | Cada `ServerNode` | Usado para deduplicación |
| Métricas | Local | Cada `ServerNode` | Consolidable al final |

---

## Regla sobre estado local y estado distribuido

No se deben distribuir objetos asociados a conexiones locales.

No se deben enviar entre nodos:

- sockets;
- `ObjectInputStream`;
- `ObjectOutputStream`;
- referencias a `ClientConnectionHandler`;
- referencias a `ManejadorCliente`;
- hilos;
- objetos de sincronización local.

Lo que se distribuye es información lógica:

~~~text
userId -> nodeId
groupId -> miembros
nodeId -> estado
messageId -> resultado
~~~

---

## Directorio global de usuarios

El `GlobalUserDirectory` mantiene la relación:

~~~text
userId -> nodeId
~~~

Ejemplo:

~~~text
ana -> node1
benja -> node2
carla -> node3
~~~

Cuando un usuario inicia sesión en un nodo, ese nodo debe anunciarlo a los demás nodos mediante `USER_LOGIN_ANNOUNCE`.

Cuando un usuario se desconecta, el nodo debe anunciarlo mediante `USER_LOGOUT_ANNOUNCE`.

---

## Gestión de sesiones locales

Cada nodo mantiene solo sus propias sesiones locales.

Ejemplo:

~~~text
node1:
  LocalSessionManager:
    ana -> ClientConnectionHandler
    igna -> ClientConnectionHandler

node2:
  LocalSessionManager:
    benja -> ClientConnectionHandler

node3:
  LocalSessionManager:
    carla -> ClientConnectionHandler
~~~

Si `node1` necesita enviar un mensaje a `benja`, no debe tener una referencia directa a su socket. Debe consultar el directorio y reenviar el mensaje a `node2`.

---

## Gestión distribuida de grupos

El `DistributedGroupManager` mantiene información de grupos y membresías.

Ejemplo:

~~~text
grupoA:
  ana
  benja
  carla
~~~

La membresía puede incluir usuarios conectados en distintos nodos.

El grupo se considera un recurso distribuido, porque distintos nodos pueden necesitar modificar su existencia o membresía.

---

## Recurso crítico distribuido

El recurso crítico distribuido de la arquitectura será:

~~~text
GROUP_REGISTRY
~~~

Este recurso representa el registro lógico de grupos y membresías.

Operaciones protegidas:

- crear grupo;
- unirse a grupo;
- salir de grupo;
- modificar membresía;
- replicar actualización de grupo.

---

## Justificación del recurso crítico

Si dos nodos modifican simultáneamente `GROUP_REGISTRY`, pueden aparecer inconsistencias.

Ejemplos:

- `node1` crea `grupoX` con miembros A y B.
- `node2` crea `grupoX` con miembros C y D.
- `node3` recibe una actualización, pero no la otra.
- un usuario queda como miembro en un nodo y ausente en otro;
- un mensaje grupal se entrega con una membresía desactualizada.

Por esto, las modificaciones de grupos deben coordinarse mediante exclusión mutua distribuida.

---

## Coordinación distribuida

La coordinación distribuida se realizará mediante:

~~~text
Relojes de Lamport + Ricart-Agrawala
~~~

Uso esperado:

1. Nodo solicita entrar a sección crítica para modificar `GROUP_REGISTRY`.
2. Incrementa su reloj de Lamport.
3. Envía `MUTEX_REQUEST` a los demás nodos.
4. Espera `MUTEX_REPLY`.
5. Entra a sección crítica.
6. Modifica el grupo.
7. Replica el cambio mediante `GROUP_UPDATE_COMMIT`.
8. Sale de sección crítica.
9. Responde solicitudes diferidas.

---

## Relojes de Lamport

Cada `ServerNode` mantiene un reloj lógico local.

Reglas:

1. Antes de un evento local relevante, incrementa Lamport.
2. Antes de enviar un mensaje inter-nodo, incrementa Lamport y adjunta el valor.
3. Al recibir un mensaje inter-nodo, actualiza:

~~~text
L_local = max(L_local, L_recibido) + 1
~~~

Ejemplo de log:

~~~text
[node1][L=12] SEND PRIVATE_MESSAGE_FORWARD to node2
[node2][L=17] RECEIVE PRIVATE_MESSAGE_FORWARD from node1
~~~

---

## Eventos que deben registrarse con Lamport

Como mínimo, deben registrarse:

- inicio de nodo;
- conexión de peer;
- login de usuario;
- logout de usuario;
- anuncio de usuario conectado;
- envío de mensaje privado remoto;
- recepción de mensaje privado remoto;
- envío de mensaje grupal remoto;
- recepción de mensaje grupal remoto;
- solicitud de sección crítica;
- respuesta de sección crítica;
- entrada a sección crítica;
- salida de sección crítica;
- commit de actualización de grupo;
- heartbeat enviado;
- heartbeat recibido;
- timeout de heartbeat;
- nodo marcado como `SUSPECTED`;
- nodo marcado como `DOWN`;
- nodo reintegrado.

---

## Contrato general de mensajes entre nodos

Todo mensaje entre nodos debe cumplir con una estructura base conceptual:

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

Tipos mínimos:

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

## Mensajes que Persona 2 debe dejar funcionales

Persona 2 no debe implementar todo el contrato de mensajes. Su foco es la infraestructura inter-nodo y la membresía inicial.

| Mensaje | Obligatorio para Persona 2 | Motivo |
|---|---:|---|
| `PEER_HELLO` | Sí | Permite presentación inicial entre nodos |
| `PEER_HELLO_ACK` | Sí | Permite confirmar que el peer fue aceptado |
| `MEMBERSHIP_UPDATE` | Parcial | Puede quedar básico o preparado |
| `NODE_ERROR` | Básico | Manejo mínimo de errores entre nodos |
| `PRIVATE_MESSAGE_FORWARD` | No | Persona 3 |
| `GROUP_MESSAGE_FORWARD` | No | Persona 3 |
| `MUTEX_REQUEST` | No | Persona 4 |
| `MUTEX_REPLY` | No | Persona 4 |
| `HEARTBEAT` | No completo | Persona 5 |
| `HEARTBEAT_ACK` | No completo | Persona 5 |

---

## Advertencia técnica sobre Object streams

Al implementar comunicación TCP con objetos serializados, se debe evitar el bloqueo por creación simétrica de streams.

Regla recomendada:

~~~java
ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
out.flush();
ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
~~~

No se debe crear primero `ObjectInputStream` en ambos extremos al mismo tiempo, porque puede producir bloqueo esperando el encabezado del stream.

---

## Función principal 1: mensajería privada distribuida

### Descripción

Un cliente conectado a un nodo puede enviar un mensaje privado a un usuario conectado a otro nodo.

El cliente emisor no conoce en qué nodo está el destinatario. Solo conoce su identificador lógico de usuario.

---

### Flujo general

~~~text
Cliente A -> node1 -> node2 -> Cliente B
~~~

Pasos:

1. Cliente A envía mensaje privado a Cliente B.
2. `node1` recibe el mensaje.
3. `node1` consulta `LocalSessionManager`.
4. Si Cliente B está local, entrega directamente.
5. Si Cliente B no está local, consulta `GlobalUserDirectory`.
6. `node1` determina que Cliente B está en `node2`.
7. `node1` envía `PRIVATE_MESSAGE_FORWARD` a `node2`.
8. `node2` recibe el mensaje.
9. `node2` actualiza Lamport.
10. `node2` busca a Cliente B en su `LocalSessionManager`.
11. `node2` entrega el mensaje.
12. `node2` responde `PRIVATE_MESSAGE_ACK`.
13. `node1` registra resultado y métricas.

---

### Errores posibles

| Error | Acción esperada |
|---|---|
| Destinatario no existe | Informar al emisor |
| Destinatario no conectado | Informar al emisor |
| Nodo destino caído | Informar fallo remoto |
| Timeout inter-nodo | Registrar error y actualizar métricas |
| Mensaje duplicado | Ignorar efecto y responder ACK de duplicado |
| Error de socket | Cerrar conexión y limpiar estado |

---

## Función principal 2: mensajería grupal distribuida

### Descripción

Un cliente conectado a cualquier nodo puede enviar un mensaje a un grupo cuyos miembros están conectados en distintos nodos.

---

### Flujo general

~~~text
Cliente A -> node1
node1 -> miembros locales
node1 -> node2 -> miembros locales en node2
node1 -> node3 -> miembros locales en node3
~~~

Pasos:

1. Cliente A envía mensaje al grupo `grupoX`.
2. `node1` valida que el grupo exista.
3. `node1` valida que Cliente A pertenezca al grupo.
4. `node1` obtiene miembros desde `DistributedGroupManager`.
5. `node1` separa miembros locales y remotos.
6. `node1` entrega el mensaje a miembros locales.
7. `node1` envía `GROUP_MESSAGE_FORWARD` a nodos con miembros remotos.
8. Cada nodo remoto entrega a sus miembros locales.
9. Cada nodo remoto responde `GROUP_MESSAGE_ACK`.
10. `node1` registra métricas.

---

### Errores posibles

| Error | Acción esperada |
|---|---|
| Grupo inexistente | Rechazar solicitud |
| Emisor no pertenece al grupo | Rechazar solicitud |
| Nodo remoto caído | Registrar entrega parcial |
| Miembro desconectado | Omitir o informar fallo parcial |
| Mensaje duplicado | Evitar doble entrega |
| Timeout de ACK | Registrar omisión |

---

## Membresía de nodos

Cada nodo debe conocer a los demás nodos mediante configuración inicial.

Ejemplo para `node1`:

~~~properties
node.peers=node2@localhost:5002:6002,node3@localhost:5003:6003
~~~

Estados posibles:

~~~java
public enum NodeStatus {
    ALIVE,
    SUSPECTED,
    DOWN,
    RECOVERING
}
~~~

---

## Detección de fallos

La detección de fallos se realiza mediante heartbeats y timeouts.

### Flujo normal

~~~text
node1 -> HEARTBEAT -> node2
node2 -> HEARTBEAT_ACK -> node1
~~~

### Flujo con falla

~~~text
node1 envía HEARTBEAT a node2
node2 no responde dentro del timeout
node1 marca node2 como SUSPECTED
node1 supera el umbral
node1 marca node2 como DOWN
node1 evita enrutar mensajes nuevos hacia node2
~~~

---

## Supuestos del sistema

### Supuesto de red

Se usa TCP para comunicación cliente-servidor y servidor-servidor.

TCP entrega bytes ordenados dentro de una conexión, pero la conexión puede fallar, bloquearse, cerrarse o exceder timeouts.

---

### Supuesto de nodos

Se consideran fallos:

- crash;
- omisión;
- desconexión;
- timeout;
- error de socket.

No se considera tolerancia bizantina.

---

### Supuesto de sincronía

El sistema se modela como parcialmente sincrónico para fines prácticos.

Los timeouts permiten sospechar fallos, pero no demuestran con certeza absoluta que un nodo cayó.

---

### Supuesto de tiempo

No existe reloj global compartido.

`System.currentTimeMillis()` solo se usa para métricas, no para ordenar eventos distribuidos.

El orden lógico se registra mediante Lamport.

---

## Transparencia del sistema

### Transparencia de ubicación

Los clientes no necesitan saber dónde está físicamente conectado otro usuario.

Ejemplo:

~~~text
Cliente A envía mensaje a "benja"
~~~

El sistema resuelve internamente si `benja` está en:

~~~text
node1
node2
node3
~~~

---

### Transparencia de acceso

El cliente usa los mismos comandos o paquetes lógicos para enviar mensajes, sin importar si el destinatario está local o remoto.

La diferencia entre entrega local y entrega remota queda encapsulada en `MessageRouter`.

---

## Estructura de paquetes recomendada

Se recomienda mantener una única estructura coherente para evitar duplicidad de clases.

~~~text
src/main/java/whatsapp/
├── client/
│   └── ClienteNodo.java
│
├── common/
│   └── models/
│       ├── PaqueteRed.java
│       ├── PaqueteLogin.java
│       ├── PaqueteMensaje.java
│       └── ...
│
└── server/
    ├── ServerNode.java
    ├── ServerNodeContext.java
    ├── config/
    │   └── NodeConfig.java
    ├── node/
    │   ├── NodeInfo.java
    │   └── NodeStatus.java
    ├── messages/
    │   ├── NodeMessage.java
    │   ├── NodeMessageType.java
    │   ├── PeerHelloMessage.java
    │   ├── PeerHelloAckMessage.java
    │   └── MembershipUpdateMessage.java
    ├── membership/
    │   └── MembershipManager.java
    ├── directory/
    │   └── GlobalUserDirectory.java
    ├── managers/
    │   ├── LocalSessionManager.java
    │   └── DistributedGroupManager.java
    ├── routing/
    │   └── MessageRouter.java
    ├── peer/
    │   ├── PeerTransport.java
    │   ├── NoOpPeerTransport.java
    │   ├── TcpPeerTransport.java
    │   ├── PeerListener.java
    │   ├── PeerConnectionManager.java
    │   └── PeerMessageHandler.java
    ├── time/
    │   └── LamportClock.java
    ├── coordination/
    │   └── MutualExclusionManager.java
    ├── failure/
    │   ├── HeartbeatManager.java
    │   └── FailureDetector.java
    └── metrics/
        └── MetricsCollector.java
~~~

---

## Diagramas asociados

Los diagramas recomendados para acompañar esta arquitectura son:

~~~text
docs/diagrams/modelo-fisico-final.puml
docs/diagrams/arquitectura-final.puml
docs/diagrams/seq-mensaje-privado-distribuido.puml
docs/diagrams/seq-mensaje-grupal-distribuido.puml
docs/diagrams/seq-group-registry-coordinado.puml
~~~

---

## Diagrama físico sugerido

~~~plantuml
@startuml
title Modelo físico final - Arquitectura multiservidor

node "Cliente A" as cA
node "Cliente B" as cB
node "Cliente C" as cC

node "ServerNode node1\nclientPort: 5001\npeerPort: 6001" as n1
node "ServerNode node2\nclientPort: 5002\npeerPort: 6002" as n2
node "ServerNode node3\nclientPort: 5003\npeerPort: 6003" as n3

cloud "Red TCP/IP / LAN" as net

cA --> n1 : TCP cliente-servidor
cB --> n2 : TCP cliente-servidor
cC --> n3 : TCP cliente-servidor

n1 <--> n2 : TCP inter-nodo
n1 <--> n3 : TCP inter-nodo
n2 <--> n3 : TCP inter-nodo

@enduml
~~~

---

## Diagrama arquitectónico sugerido

~~~plantuml
@startuml
title Arquitectura interna de ServerNode

package "ServerNode" {

  component "ClientAcceptor" as CA
  component "clientWorkerPool\nExecutorService" as CWP
  component "ClientConnectionHandler" as CCH

  component "PeerListener" as PL
  component "peerWorkerPool\nExecutorService" as PWP
  component "PeerMessageHandler" as PMH

  component "schedulerPool\nScheduledExecutorService" as SP
  component "HeartbeatManager" as HB
  component "FailureDetector" as FD

  component "coordinationExecutor\nSingleThreadExecutor" as CE
  component "MutualExclusionManager\nRicart-Agrawala" as MEM

  component "MessageRouter" as MR
  component "LocalSessionManager" as LSM
  component "GlobalUserDirectory" as GUD
  component "DistributedGroupManager" as DGM
  component "MembershipManager" as MM
  component "LamportClock" as LC
  component "MetricsCollector" as MC
}

CA --> CWP
CWP --> CCH
CCH --> MR

PL --> PWP
PWP --> PMH
PMH --> MR
PMH --> CE

SP --> HB
SP --> FD

CE --> MEM
MEM --> LC

MR --> LSM
MR --> GUD
MR --> DGM
MR --> MM
MR --> LC
MR --> MC

HB --> MM
FD --> MM

@enduml
~~~

---

## Métricas esperadas

La arquitectura debe permitir registrar:

| Métrica | Propósito |
|---|---|
| Throughput | Medir mensajes o solicitudes por segundo |
| Latencia promedio | Medir respuesta media |
| Latencia p95 | Medir peor caso razonable |
| Mensajes de coordinación | Medir costo de Ricart-Agrawala |
| Errores o pérdidas | Medir fallos normales o inducidos |
| Tiempo de recuperación | Medir respuesta ante caída de nodo |
| Heartbeat timeouts | Evidenciar detección de fallos |
| Tareas rechazadas por pool | Evidenciar saturación |
| Active workers | Observar carga por pool |

---

## Criterios de aceptación arquitectónica

La arquitectura se considera correctamente definida si cumple:

1. Existen al menos tres `ServerNode`.
2. Cada nodo tiene puerto de clientes y puerto inter-nodo.
3. Cada nodo tiene identidad lógica estable.
4. Los nodos conocen a sus peers mediante configuración o membresía.
5. Los clientes pueden conectarse a nodos distintos.
6. Un mensaje privado puede viajar entre nodos.
7. Un mensaje grupal puede entregarse entre nodos.
8. El sistema no depende de un broker central oculto.
9. Cada nodo usa thread-pools separados.
10. Los heartbeats no dependen del pool de clientes.
11. La coordinación no depende del pool de clientes.
12. Los eventos distribuidos usan Lamport.
13. El recurso `GROUP_REGISTRY` está definido como crítico.
14. La caída de un nodo no detiene todo el sistema.
15. El diseño permite ejecutar prueba de carga con 50 clientes/hilos durante 60 segundos.

---

## Handoff para Persona 2

Persona 2 debe implementar la base técnica de comunicación entre nodos.

Debe completar o adaptar la base entregada por Persona 1:

- `ServerNode`;
- `NodeConfig`;
- `NodeInfo`;
- `NodeStatus`;
- `NodeMessage`;
- `NodeMessageType`;
- `MembershipManager`;
- `PeerTransport`;
- `NoOpPeerTransport`.

Debe crear o completar:

- `TcpPeerTransport`;
- `PeerListener`;
- `PeerConnectionManager`;
- `PeerMessageHandler`;
- `MembershipUpdateMessage`, si se usará propagación explícita de membresía.

Debe respetar:

- no usar `new Thread(...)` por cada cliente o peer;
- inicializar `clientWorkerPool`;
- inicializar `peerWorkerPool`;
- inicializar `schedulerPool`;
- inicializar `coordinationExecutor`;
- cargar configuración desde `.properties`;
- permitir ejecutar tres nodos en paralelo;
- no crear un broker central oculto;
- no mezclar mensajes inter-nodo con el pool de clientes.

Resultado esperado:

~~~text
[node1] Peer detectado: node2
[node1] Peer detectado: node3
[node2] Peer detectado: node1
[node2] Peer detectado: node3
[node3] Peer detectado: node1
[node3] Peer detectado: node2
~~~

---

## Handoff para Persona 3

Persona 3 debe implementar las funciones distribuidas.

Debe adaptar:

- login;
- logout;
- mensaje privado;
- creación de grupo;
- unión a grupo;
- mensaje grupal.

Debe usar:

- `LocalSessionManager`;
- `GlobalUserDirectory`;
- `DistributedGroupManager`;
- `MessageRouter`;
- transporte TCP real entregado por Persona 2.

Resultado esperado:

~~~text
Cliente A conectado a node1 envía mensaje privado a Cliente B conectado a node2.
Cliente C conectado a node3 recibe mensaje grupal enviado desde node1.
~~~

---

## Handoff para Persona 4

Persona 4 debe implementar:

- `LamportClock`;
- timestamps en `NodeMessage`;
- logs con Lamport;
- Ricart-Agrawala;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- cola de solicitudes diferidas;
- entrada y salida de sección crítica.

Recurso protegido:

~~~text
GROUP_REGISTRY
~~~

Resultado esperado:

~~~text
[node1][L=15] REQUEST critical section GROUP_REGISTRY
[node2][L=18] REPLY to node1
[node3][L=20] REPLY to node1
[node1][L=21] ENTER critical section
[node1][L=25] EXIT critical section
~~~

---

## Handoff para Persona 5

Persona 5 debe implementar:

- `HeartbeatManager`;
- `FailureDetector`;
- envío periódico de heartbeats;
- timeouts;
- estados de nodo;
- reconfiguración básica ante caída;
- recuperación parcial.

Estados:

~~~text
ALIVE
SUSPECTED
DOWN
RECOVERING
~~~

Resultado esperado:

~~~text
[node1] Heartbeat timeout: node2
[node1] node2 marcado como SUSPECTED
[node1] node2 marcado como DOWN
[node3] Servicio continúa activo
~~~

---

## Handoff para Persona 6

Persona 6 debe validar la arquitectura mediante:

- `LoadGenerator`;
- 50 clientes/hilos concurrentes;
- duración mínima de 60 segundos;
- mensajes privados;
- mensajes grupales;
- operaciones sobre sección crítica;
- falla inducida;
- métricas;
- logs finales;
- gráficos;
- informe final.

Métricas mínimas:

~~~text
throughput
latencia promedio
latencia p95
mensajes de coordinación
errores/pérdidas
tiempo de recuperación
~~~

---

## Riesgos arquitectónicos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Mantener un broker central oculto | Incumplimiento de arquitectura distribuida | Cada nodo debe enrutar y comunicarse con peers |
| Sobrecargar el pool de clientes | Bloqueo de atención local | Configurar `pool.clients` y timeouts |
| Usar un único pool global | Heartbeats o coordinación bloqueados | Separar pools |
| Usar reloj físico como orden global | Error conceptual | Usar Lamport |
| No coordinar grupos | Inconsistencia de membresía | Proteger `GROUP_REGISTRY` |
| Prometer tolerancia bizantina | Sobrealcance | Declararla fuera de alcance |
| Reintentos sin deduplicación | Doble entrega o doble commit | Usar `messageId` |
| No medir desde el inicio | Falta de evidencia final | Incorporar `MetricsCollector` |
| Crear primero `ObjectInputStream` en ambos extremos | Bloqueo de conexión | Crear primero `ObjectOutputStream` y hacer `flush()` |

---

## Fuera de alcance

La arquitectura final no busca implementar:

- tolerancia a fallos bizantinos;
- cifrado TLS completo;
- autenticación productiva;
- persistencia durable de historial;
- consenso Raft completo;
- entrega exactamente una vez;
- replicación fuerte de base de datos;
- aplicación tipo WhatsApp productiva.

El objetivo es demostrar conceptos fundamentales de sistemas distribuidos dentro del alcance académico del curso.

---

## Conclusión

La arquitectura final transforma el sistema desde una solución centralizada basada en un broker único hacia una arquitectura multiservidor con nodos cooperativos.

El cambio principal no es eliminar completamente el modelo cliente-servidor, sino distribuir la capa servidora. Cada cliente sigue hablando con un nodo local, pero los nodos servidores colaboran entre sí para enrutar mensajes, mantener directorios, coordinar grupos, detectar fallos y registrar eventos distribuidos.

La arquitectura final se resume así:

~~~text
Entrega Inicial:
Cliente -> ServidorPrincipal

Entrega Final:
Cliente -> ServerNode local
ServerNode local <-> ServerNode remoto
~~~

Esta evolución permite sostener las dos funciones principales del parcial —mensajería privada y mensajería grupal—, pero ahora bajo una arquitectura que evidencia comunicación entre nodos, ausencia de reloj global, coordinación distribuida, fallos independientes y métricas de carga.