# Handoff para Persona 2 — Comunicación entre nodos, sockets y membresía

## Propósito del documento

Este documento entrega a la Persona 2 las directrices técnicas necesarias para implementar la base de comunicación entre nodos de la Entrega Final del sistema de mensajería instantánea inspirado en WhatsApp.

La Persona 1 deja definida la arquitectura general. La Persona 2 debe convertir esa arquitectura en una base funcional donde existan tres o más `ServerNode` reales, ejecutándose como procesos independientes y comunicándose entre sí mediante sockets TCP.

El objetivo de este handoff es que la Persona 2 pueda implementar la capa multiservidor sin tener que redefinir decisiones arquitectónicas.

---

## Objetivo de Persona 2

La Persona 2 debe implementar la infraestructura mínima para que los nodos servidores se conozcan, se conecten y puedan intercambiar mensajes inter-nodo.

Al finalizar su trabajo, debe ser posible ejecutar tres nodos:

~~~bash
java whatsapp.server.ServerNode node1 5001 6001 config/node1.properties
java whatsapp.server.ServerNode node2 5002 6002 config/node2.properties
java whatsapp.server.ServerNode node3 5003 6003 config/node3.properties
~~~

Y los logs deberían mostrar evidencia similar a:

~~~text
[node1] Peer detectado: node2
[node1] Peer detectado: node3
[node2] Peer detectado: node1
[node2] Peer detectado: node3
[node3] Peer detectado: node1
[node3] Peer detectado: node2
~~~

---

## Alcance de Persona 2

Persona 2 debe implementar:

- creación de `ServerNode`;
- carga de configuración por nodo;
- identificación lógica de nodos;
- puerto para clientes;
- puerto inter-nodo;
- conexión TCP entre nodos;
- recepción de mensajes inter-nodo;
- envío de mensajes inter-nodo;
- estructura base de `NodeMessage`;
- lista de membresía inicial;
- logs básicos de conexión;
- thread-pools internos de `ServerNode`.

Persona 2 no debe implementar completamente:

- lógica final de mensaje privado distribuido;
- lógica final de mensaje grupal distribuido;
- Ricart-Agrawala completo;
- Lamport completo;
- heartbeats completos;
- prueba de carga final.

Sin embargo, debe dejar la base lista para que esas partes se integren después.

---

## Decisiones arquitectónicas que Persona 2 debe respetar

### 1. Arquitectura multiservidor

La Entrega Final usa una arquitectura multiservidor.

Cada nodo es una instancia independiente de `ServerNode`.

Cada `ServerNode` cumple dos roles:

1. Servidor de clientes locales.
2. Peer distribuido que se comunica con otros `ServerNode`.

---

### 2. No debe existir broker central único

Persona 2 no debe crear un nuevo servidor maestro oculto.

No debe existir un nodo especial que concentre toda la comunicación.

Los tres nodos deben ser equivalentes desde el punto de vista de la comunicación inter-nodo.

Permitido:

~~~text
node1 <-> node2
node1 <-> node3
node2 <-> node3
~~~

No permitido:

~~~text
node1 -> servidor maestro
node2 -> servidor maestro
node3 -> servidor maestro
~~~

---

### 3. Cada nodo debe tener dos puertos

Cada nodo debe separar:

- puerto de clientes;
- puerto inter-nodo.

| Nodo | Puerto clientes | Puerto inter-nodo |
|---|---:|---:|
| `node1` | `5001` | `6001` |
| `node2` | `5002` | `6002` |
| `node3` | `5003` | `6003` |

---

### 4. Cada nodo debe tener identidad lógica

La identidad principal del nodo es su `nodeId`.

Ejemplos:

~~~text
node1
node2
node3
~~~

La IP y los puertos son atributos de conexión, pero no deben reemplazar la identidad lógica.

---

### 5. No usar Thread-per-Connection ilimitado

Persona 2 debe implementar `ServerNode` usando thread-pools acotados.

No debe usar:

~~~java
new Thread(handler).start();
~~~

para cada cliente o peer sin control.

Debe usar:

~~~java
clientWorkerPool.submit(handler);
peerWorkerPool.submit(handler);
~~~

---

## Clases que Persona 2 debe crear o adaptar

### Clases obligatorias

| Clase | Responsabilidad |
|---|---|
| `ServerNode` | Proceso principal de cada nodo servidor |
| `NodeInfo` | Representa información de un nodo |
| `NodeConfig` | Carga configuración desde archivo o argumentos |
| `NodeStatus` | Enum con estado del nodo |
| `MembershipManager` | Mantiene lista de nodos conocidos |
| `PeerListener` | Escucha conexiones o mensajes de otros nodos |
| `PeerConnectionManager` | Envía mensajes hacia otros nodos |
| `PeerMessageHandler` | Procesa mensajes entrantes desde peers |
| `NodeMessage` | Clase base para mensajes entre nodos |
| `NodeMessageType` | Enum con tipos de mensajes inter-nodo |
| `PeerHelloMessage` | Mensaje de presentación entre nodos |
| `PeerHelloAckMessage` | ACK de presentación |
| `MembershipUpdateMessage` | Mensaje de actualización de membresía |

---

## Paquetes sugeridos

Estructura recomendada:

~~~text
src/main/java/whatsapp/
├── client/
│   └── ClienteNodo.java
│
├── common/
│   ├── models/
│   └── network/
│
├── server/
│   ├── ServerNode.java
│   ├── config/
│   │   └── NodeConfig.java
│   ├── node/
│   │   ├── NodeInfo.java
│   │   └── NodeStatus.java
│   ├── membership/
│   │   └── MembershipManager.java
│   ├── peer/
│   │   ├── PeerListener.java
│   │   ├── PeerConnectionManager.java
│   │   └── PeerMessageHandler.java
│   ├── messages/
│   │   ├── NodeMessage.java
│   │   ├── NodeMessageType.java
│   │   ├── PeerHelloMessage.java
│   │   ├── PeerHelloAckMessage.java
│   │   └── MembershipUpdateMessage.java
│   └── managers/
│       ├── LocalSessionManager.java
│       └── DistributedGroupManager.java
~~~

---

## Configuración de nodos

Persona 2 debe permitir iniciar cada nodo mediante argumentos y/o archivo `.properties`.

### Archivos requeridos

~~~text
config/
├── node1.properties
├── node2.properties
└── node3.properties
~~~

### Ejemplo: `config/node1.properties`

~~~properties
node.id=node1
node.host=localhost
node.clientPort=5001
node.peerPort=6001

node.peers=node2@localhost:6002,node3@localhost:6003

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000
~~~

### Ejemplo: `config/node2.properties`

~~~properties
node.id=node2
node.host=localhost
node.clientPort=5002
node.peerPort=6002

node.peers=node1@localhost:6001,node3@localhost:6003

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000
~~~

### Ejemplo: `config/node3.properties`

~~~properties
node.id=node3
node.host=localhost
node.clientPort=5003
node.peerPort=6003

node.peers=node1@localhost:6001,node2@localhost:6002

pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1

socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000
~~~

---

## Clase `NodeInfo`

### Responsabilidad

Representar la identidad y datos de conexión de un nodo.

### Campos mínimos

~~~java
public class NodeInfo implements Serializable {
    private String nodeId;
    private String host;
    private int clientPort;
    private int peerPort;
    private NodeStatus status;
    private long lastSeenMillis;
}
~~~

### Consideraciones

- `nodeId` debe ser único.
- `host` y `peerPort` se usan para conectar entre nodos.
- `clientPort` permite saber dónde aceptar clientes.
- `status` se usará después para fallos.
- `lastSeenMillis` se usará después para heartbeats.

---

## Enum `NodeStatus`

Debe considerar al menos:

~~~java
public enum NodeStatus {
    ALIVE,
    SUSPECTED,
    DOWN,
    RECOVERING
}
~~~

Persona 2 puede inicializar todos los peers conocidos como `ALIVE` si logra conexión inicial. La lógica completa de heartbeats y cambio de estado será responsabilidad posterior de Persona 5.

---

## Clase `NodeConfig`

### Responsabilidad

Cargar configuración del nodo desde argumentos y/o archivo `.properties`.

### Campos mínimos

~~~java
public class NodeConfig {
    private String nodeId;
    private String host;
    private int clientPort;
    private int peerPort;
    private List<NodeInfo> peers;

    private int clientPoolSize;
    private int peerPoolSize;
    private int schedulerPoolSize;
    private int coordinationPoolSize;

    private int clientSocketTimeoutMs;
    private int peerSocketTimeoutMs;
    private int heartbeatIntervalMs;
    private int heartbeatTimeoutMs;
}
~~~

### Regla

Si los argumentos de línea de comando y el archivo `.properties` entregan el mismo dato, se debe definir una prioridad clara.

Recomendación:

1. Argumentos de línea de comando.
2. Archivo `.properties`.
3. Valores por defecto.

---

## Clase `ServerNode`

### Responsabilidad

Ser el proceso principal del nodo servidor.

Debe:

1. Cargar configuración.
2. Inicializar identidad del nodo.
3. Inicializar thread-pools.
4. Inicializar managers.
5. Levantar listener de clientes.
6. Levantar listener inter-nodo.
7. Conectarse a peers iniciales.
8. Registrar logs de inicio.

### Estructura conceptual

~~~java
public class ServerNode {
    private final NodeConfig config;
    private final NodeInfo selfInfo;

    private final ExecutorService clientWorkerPool;
    private final ExecutorService peerWorkerPool;
    private final ScheduledExecutorService schedulerPool;
    private final ExecutorService coordinationExecutor;

    private final MembershipManager membershipManager;
    private final PeerConnectionManager peerConnectionManager;
    private final PeerListener peerListener;

    public ServerNode(NodeConfig config) {
        this.config = config;
        this.selfInfo = config.toNodeInfo();

        this.clientWorkerPool =
            Executors.newFixedThreadPool(config.getClientPoolSize());

        this.peerWorkerPool =
            Executors.newFixedThreadPool(config.getPeerPoolSize());

        this.schedulerPool =
            Executors.newScheduledThreadPool(config.getSchedulerPoolSize());

        this.coordinationExecutor =
            Executors.newSingleThreadExecutor();

        this.membershipManager = new MembershipManager(selfInfo, config.getPeers());
        this.peerConnectionManager = new PeerConnectionManager(selfInfo, membershipManager);
        this.peerListener = new PeerListener(selfInfo, peerWorkerPool, membershipManager);
    }

    public void start() {
        peerListener.start();
        peerConnectionManager.connectToInitialPeers();
        // client listener se integra con lógica existente o Persona 3.
    }
}
~~~

---

## Thread-pools obligatorios

Persona 2 debe inicializar estos pools:

| Pool | Tipo | Tamaño sugerido |
|---|---|---:|
| `clientWorkerPool` | `ExecutorService` | `64` |
| `peerWorkerPool` | `ExecutorService` | `16` |
| `schedulerPool` | `ScheduledExecutorService` | `4` |
| `coordinationExecutor` | `ExecutorService` | `1` |

### Regla importante

No mezclar responsabilidades.

| Responsabilidad | Pool correcto |
|---|---|
| Clientes locales | `clientWorkerPool` |
| Mensajes entre nodos | `peerWorkerPool` |
| Heartbeats/timeouts | `schedulerPool` |
| Coordinación distribuida | `coordinationExecutor` |

---

## Clase `NodeMessage`

### Responsabilidad

Ser la clase base de todos los mensajes entre nodos.

### Campos mínimos

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

### Consideraciones

- `messageId` permite trazabilidad y deduplicación.
- `sourceNodeId` identifica el nodo emisor.
- `targetNodeId` identifica el nodo receptor.
- `type` permite despachar el mensaje.
- `lamportTimestamp` se usará después por Persona 4.
- `sentAtMillis` se usa solo para métricas, no para ordenar eventos.

Persona 2 puede inicializar `lamportTimestamp` en `0` si Lamport aún no está implementado. La clase debe dejar el campo preparado.

---

## Enum `NodeMessageType`

Debe incluir al menos:

~~~java
public enum NodeMessageType {
    PEER_HELLO,
    PEER_HELLO_ACK,
    MEMBERSHIP_UPDATE,

    USER_LOGIN_ANNOUNCE,
    USER_LOGOUT_ANNOUNCE,

    PRIVATE_MESSAGE_FORWARD,
    PRIVATE_MESSAGE_ACK,

    GROUP_MESSAGE_FORWARD,
    GROUP_MESSAGE_ACK,

    MUTEX_REQUEST,
    MUTEX_REPLY,

    HEARTBEAT,
    HEARTBEAT_ACK,

    NODE_ERROR
}
~~~

Persona 2 debe implementar funcionalmente al menos:

- `PEER_HELLO`;
- `PEER_HELLO_ACK`;
- `MEMBERSHIP_UPDATE`.

Los demás tipos pueden quedar definidos para integración posterior.

---

## Mensaje `PeerHelloMessage`

### Responsabilidad

Permitir que un nodo se presente ante otro nodo.

### Campos sugeridos

~~~java
public class PeerHelloMessage extends NodeMessage {
    private NodeInfo nodeInfo;
    private List<NodeInfo> knownPeers;
}
~~~

### Flujo esperado

~~~text
node1 inicia.
node1 lee que node2 y node3 son peers.
node1 envía PEER_HELLO a node2.
node1 envía PEER_HELLO a node3.
node2 registra node1.
node3 registra node1.
node2 y node3 responden PEER_HELLO_ACK.
~~~

---

## Mensaje `PeerHelloAckMessage`

### Responsabilidad

Confirmar que un peer recibió y aceptó la presentación.

### Campos sugeridos

~~~java
public class PeerHelloAckMessage extends NodeMessage {
    private boolean accepted;
    private NodeInfo receiverNodeInfo;
    private List<NodeInfo> knownPeers;
}
~~~

---

## Clase `MembershipManager`

### Responsabilidad

Mantener el estado conocido de los nodos.

### Campos conceptuales

~~~java
public class MembershipManager {
    private final NodeInfo self;
    private final ConcurrentHashMap<String, NodeInfo> nodesById;
}
~~~

### Métodos mínimos

~~~java
public void addOrUpdateNode(NodeInfo nodeInfo);
public Optional<NodeInfo> getNode(String nodeId);
public List<NodeInfo> getAliveNodes();
public List<NodeInfo> getAllNodes();
public void markAlive(String nodeId);
public void markSuspected(String nodeId);
public void markDown(String nodeId);
~~~

### Reglas

- No agregar `self` como peer remoto.
- No duplicar nodos por IP/puerto si tienen el mismo `nodeId`.
- Actualizar `lastSeenMillis` cuando se recibe un mensaje válido de un nodo.
- Registrar logs cuando aparece un nuevo peer.

---

## Clase `PeerListener`

### Responsabilidad

Escuchar conexiones o mensajes desde otros nodos usando el puerto inter-nodo.

Debe usar `ServerSocket` sobre `peerPort`.

### Funcionamiento conceptual

~~~java
public class PeerListener implements Runnable {
    private final NodeInfo self;
    private final ExecutorService peerWorkerPool;

    public void run() {
        ServerSocket serverSocket = new ServerSocket(self.getPeerPort());

        while (running) {
            Socket peerSocket = serverSocket.accept();
            peerWorkerPool.submit(new PeerMessageHandler(peerSocket));
        }
    }
}
~~~

### Regla

`PeerListener` no debe procesar el mensaje completo en el hilo de aceptación. Solo debe aceptar la conexión y delegar al `peerWorkerPool`.

---

## Clase `PeerMessageHandler`

### Responsabilidad

Leer y procesar un `NodeMessage` recibido desde otro nodo.

### Flujo mínimo

1. Leer objeto desde `ObjectInputStream`.
2. Validar que sea instancia de `NodeMessage`.
3. Validar `sourceNodeId`.
4. Actualizar membresía básica.
5. Despachar según `NodeMessageType`.
6. Responder si corresponde.

### Pseudoflujo

~~~java
Object obj = input.readObject();

if (!(obj instanceof NodeMessage)) {
    log("Mensaje inválido desde peer");
    return;
}

NodeMessage message = (NodeMessage) obj;

membershipManager.markAlive(message.getSourceNodeId());

switch (message.getType()) {
    case PEER_HELLO:
        handlePeerHello((PeerHelloMessage) message);
        break;

    case PEER_HELLO_ACK:
        handlePeerHelloAck((PeerHelloAckMessage) message);
        break;

    case MEMBERSHIP_UPDATE:
        handleMembershipUpdate((MembershipUpdateMessage) message);
        break;

    default:
        log("Tipo de mensaje aún no implementado: " + message.getType());
}
~~~

---

## Clase `PeerConnectionManager`

### Responsabilidad

Enviar mensajes a otros nodos.

### Métodos mínimos

~~~java
public void sendToNode(String targetNodeId, NodeMessage message);
public void broadcastToPeers(NodeMessage message);
public void connectToInitialPeers();
public boolean isReachable(String nodeId);
~~~

### Reglas

- Debe usar `NodeInfo.host` y `NodeInfo.peerPort`.
- Debe manejar excepciones de conexión.
- Debe registrar error si un peer no responde.
- No debe matar el proceso completo si falla un peer.
- Debe permitir que Persona 5 conecte lógica de fallos después.

### Ejemplo conceptual

~~~java
public void sendToNode(String targetNodeId, NodeMessage message) {
    NodeInfo target = membershipManager.getNode(targetNodeId)
        .orElseThrow(() -> new IllegalArgumentException("Nodo desconocido: " + targetNodeId));

    try (Socket socket = new Socket(target.getHost(), target.getPeerPort());
         ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

        out.writeObject(message);
        out.flush();

    } catch (IOException e) {
        log("No se pudo enviar mensaje a " + targetNodeId + ": " + e.getMessage());
        membershipManager.markSuspected(targetNodeId);
    }
}
~~~

---

## Comunicación inter-nodo mínima esperada

Persona 2 debe garantizar este flujo mínimo:

~~~text
1. node1 inicia PeerListener en puerto 6001.
2. node2 inicia PeerListener en puerto 6002.
3. node3 inicia PeerListener en puerto 6003.
4. node1 lee peers node2 y node3.
5. node1 envía PEER_HELLO a node2 y node3.
6. node2 y node3 responden PEER_HELLO_ACK.
7. Cada nodo actualiza MembershipManager.
8. Los logs muestran peers detectados.
~~~

---

## Logs mínimos requeridos

Persona 2 debe registrar logs claros.

Ejemplos:

~~~text
[node1] Iniciando ServerNode
[node1] clientPort=5001 peerPort=6001
[node1] clientWorkerPool=64 peerWorkerPool=16 schedulerPool=4 coordinationExecutor=1
[node1] PeerListener escuchando en puerto 6001
[node1] Enviando PEER_HELLO a node2 localhost:6002
[node1] Enviando PEER_HELLO a node3 localhost:6003
[node1] PEER_HELLO_ACK recibido desde node2
[node1] PEER_HELLO_ACK recibido desde node3
[node1] Peer detectado: node2
[node1] Peer detectado: node3
~~~

En caso de error:

~~~text
[node1] ERROR conectando con node2 localhost:6002
[node1] node2 marcado como SUSPECTED
~~~

---

## Reglas de manejo de errores

Persona 2 debe manejar:

| Error | Manejo esperado |
|---|---|
| Peer no disponible | Log + marcar `SUSPECTED` |
| Puerto ocupado | Log claro + cierre ordenado |
| Archivo config inexistente | Log claro + no iniciar |
| Mensaje no serializable | Log + descartar |
| Tipo de mensaje desconocido | Log + descartar |
| Nodo duplicado | Actualizar entrada existente |
| Conexión rechazada | Log + no detener todo el sistema |

---

## Criterios de aceptación de Persona 2

La tarea de Persona 2 se considera terminada cuando:

1. Existe clase `ServerNode`.
2. Cada nodo puede iniciarse con ID, puerto de clientes, puerto inter-nodo y archivo de configuración.
3. Cada nodo inicializa `clientWorkerPool`.
4. Cada nodo inicializa `peerWorkerPool`.
5. Cada nodo inicializa `schedulerPool`.
6. Cada nodo inicializa `coordinationExecutor`.
7. Cada nodo levanta un `PeerListener` en su `peerPort`.
8. Cada nodo carga la lista inicial de peers.
9. Cada nodo puede enviar `PEER_HELLO`.
10. Cada nodo puede responder `PEER_HELLO_ACK`.
11. Cada nodo mantiene un `MembershipManager`.
12. Los logs muestran peers detectados.
13. Si un peer no está disponible, el nodo no se cae completo.
14. No se usa `new Thread(...)` ilimitado por cliente o peer.
15. El código queda preparado para que Persona 3 use `PeerConnectionManager`.

---

## Qué debe entregar Persona 2

Persona 2 debe entregar:

- código fuente de las clases creadas;
- archivos `node1.properties`, `node2.properties`, `node3.properties`;
- logs de ejecución de tres nodos;
- breve README técnico para ejecutar los nodos;
- explicación de cómo se inicializan los pools;
- explicación de cómo se registra un peer;
- explicación de qué errores básicos maneja.

---

## README técnico mínimo esperado

Persona 2 debe dejar algo como:

~~~md
# Ejecución de nodos

## Compilar

mvn clean package

## Ejecutar node1

java whatsapp.server.ServerNode node1 5001 6001 config/node1.properties

## Ejecutar node2

java whatsapp.server.ServerNode node2 5002 6002 config/node2.properties

## Ejecutar node3

java whatsapp.server.ServerNode node3 5003 6003 config/node3.properties

## Resultado esperado

Cada nodo debe mostrar por consola los peers detectados.
~~~

---

## Dependencias con otras personas

### Persona 3

Usará:

- `ServerNode`;
- `PeerConnectionManager`;
- `MembershipManager`;
- `NodeMessage`;
- `NodeMessageType`.

Necesita que Persona 2 deje funcionando el envío de mensajes entre nodos.

### Persona 4

Usará:

- `NodeMessage.lamportTimestamp`;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- `coordinationExecutor`.

Necesita que Persona 2 deje la estructura preparada.

### Persona 5

Usará:

- `MembershipManager`;
- `NodeStatus`;
- `HEARTBEAT`;
- `HEARTBEAT_ACK`;
- `schedulerPool`.

Necesita que Persona 2 deje la base para detectar y marcar nodos.

### Persona 6

Usará:

- logs de nodos;
- configuración de nodos;
- evidencia de tres procesos;
- base para prueba de carga.

---

## Advertencias importantes

1. No implementar un nuevo servidor central.
2. No usar un único puerto para todo.
3. No mezclar mensajes de clientes con mensajes inter-nodo.
4. No usar `clientWorkerPool` para heartbeats.
5. No usar `clientWorkerPool` para coordinación.
6. No eliminar compatibilidad con los paquetes serializables existentes.
7. No prometer tolerancia a fallos completa en esta etapa.
8. No bloquear el hilo de `PeerListener` procesando mensajes pesados.
9. No usar IP como identidad principal del nodo.
10. No ordenar eventos distribuidos con `System.currentTimeMillis`.

---

## Resultado esperado final de Persona 2

Al terminar la Persona 2, el sistema debe haber pasado de:

~~~text
Un único servidor aceptando clientes
~~~

a:

~~~text
Tres ServerNode independientes capaces de descubrirse y comunicarse entre sí
~~~

La entrega de Persona 2 no necesita tener todavía chat privado distribuido completo, chat grupal distribuido completo ni fallos completos, pero sí debe dejar la infraestructura necesaria para que esas funcionalidades puedan implementarse encima.