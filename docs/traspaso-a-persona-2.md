# Handoff para Persona 2 — Comunicación entre nodos, sockets y membresía

## Propósito del documento

Este documento entrega a la Persona 2 las directrices técnicas necesarias para continuar la base arquitectónica definida por Persona 1 e implementar la comunicación real entre nodos de la Entrega Final del sistema de mensajería instantánea inspirado en WhatsApp.

La Persona 1 deja definida y parcialmente implementada la arquitectura base multiservidor. La Persona 2 debe convertir esa base en una infraestructura funcional donde existan tres o más `ServerNode` reales, ejecutándose como procesos independientes y comunicándose entre sí mediante sockets TCP.

El objetivo de este handoff es que Persona 2 pueda avanzar sin redefinir decisiones arquitectónicas ya tomadas.

---

## Objetivo de Persona 2

Persona 2 debe implementar la infraestructura mínima para que los nodos servidores:

- se conozcan mediante configuración inicial;
- levanten un listener inter-nodo en su `peerPort`;
- se conecten a sus peers conocidos;
- intercambien mensajes `NodeMessage` serializados;
- actualicen una membresía básica;
- registren logs claros de conexión entre nodos;
- dejen preparada la base para Persona 3, Persona 4 y Persona 5.

Al finalizar su trabajo, debe ser posible ejecutar tres nodos:

~~~bash
java whatsapp.server.core.ServerNode config/node1.properties
java whatsapp.server.core.ServerNode config/node2.properties
java whatsapp.server.core.ServerNode config/node3.properties
~~~

También debe funcionar mediante Maven:

~~~bash
mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node1.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node2.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node3.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java
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

## Base técnica entregada por Persona 1

Persona 1 ya dejó una base técnica inicial que inicializa la arquitectura multiservidor, pero todavía no implementa comunicación TCP real entre nodos.

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

---

## Trabajo principal de Persona 2

Persona 2 debe reemplazar el transporte temporal `NoOpPeerTransport` por una implementación TCP real.

Se recomienda crear o completar:

- `TcpPeerTransport`;
- `PeerListener`;
- `PeerConnectionManager`;
- `PeerMessageHandler`;
- `MembershipUpdateMessage`, si se decide propagar membresía explícitamente.

El objetivo es que `ServerNode` deje de usar:

~~~java
new NoOpPeerTransport(config.getNodeId())
~~~

y pase a usar una implementación real basada en sockets TCP.

---

## Alcance de Persona 2

Persona 2 debe implementar o completar:

- comunicación TCP entre nodos;
- recepción de conexiones inter-nodo;
- envío de mensajes inter-nodo;
- serialización y deserialización de `NodeMessage`;
- envío de `PEER_HELLO`;
- recepción de `PEER_HELLO`;
- envío de `PEER_HELLO_ACK`;
- recepción de `PEER_HELLO_ACK`;
- actualización básica de `MembershipManager`;
- logs básicos de conexión entre nodos;
- manejo básico de errores de red;
- integración de la implementación TCP con `ServerNode`.

Persona 2 no debe implementar completamente:

- lógica final de mensaje privado distribuido;
- lógica final de mensaje grupal distribuido;
- Ricart-Agrawala completo;
- Lamport completo;
- heartbeats completos;
- detección completa de fallos;
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

Persona 2 debe respetar el modelo de thread-pools acotados ya definido por Persona 1.

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

## Configuración de nodos

Cada nodo se configura mediante un archivo `.properties`.

### Archivos requeridos

~~~text
config/
├── node1.properties
├── node2.properties
└── node3.properties
~~~

### Formato oficial de peers

El formato oficial de `node.peers` es:

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
~~~

---

## Paquetes recomendados

Persona 2 debe mantener coherencia con la estructura definida por Persona 1.

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

## Clases que Persona 2 debe completar o adaptar

### Clases ya entregadas por Persona 1

Persona 2 no debería reescribir estas clases desde cero salvo que sea necesario:

| Clase | Estado actual | Acción esperada |
|---|---|---|
| `ServerNode` | Scaffold inicial | Adaptar para usar transporte TCP real |
| `ServerNodeContext` | Scaffold inicial | Reutilizar |
| `NodeConfig` | Lee `.properties` | Reutilizar o extender |
| `NodeInfo` | Modelo base | Reutilizar |
| `NodeStatus` | Enum base | Reutilizar |
| `NodeMessage` | Clase base | Reutilizar |
| `NodeMessageType` | Enum base | Reutilizar |
| `PeerHelloMessage` | Mensaje base | Reutilizar |
| `PeerHelloAckMessage` | Mensaje base | Reutilizar |
| `MembershipManager` | Manager base | Completar con lógica de conexión |
| `PeerTransport` | Interfaz | Implementar mediante TCP |
| `NoOpPeerTransport` | Placeholder | Reemplazar en ejecución normal |

---

### Clases que Persona 2 debe crear o completar

| Clase | Responsabilidad |
|---|---|
| `TcpPeerTransport` | Implementación real de `PeerTransport` usando sockets TCP |
| `PeerListener` | Escuchar conexiones o mensajes de otros nodos |
| `PeerConnectionManager` | Enviar mensajes hacia otros nodos |
| `PeerMessageHandler` | Procesar mensajes entrantes desde peers |
| `MembershipUpdateMessage` | Mensaje de actualización de membresía, si se usa |

---

## Clase `TcpPeerTransport`

### Responsabilidad

Implementar el contrato `PeerTransport` usando sockets TCP.

Debe permitir:

- iniciar `PeerListener`;
- enviar mensajes a un nodo específico;
- hacer broadcast a peers conocidos;
- detener recursos de red;
- delegar procesamiento al `peerWorkerPool`.

### Métodos esperados

~~~java
public class TcpPeerTransport implements PeerTransport {

    public void start();

    public void stop();

    public void sendToNode(String targetNodeId, NodeMessage message);

    public void broadcast(NodeMessage message);
}
~~~

### Regla

`TcpPeerTransport` debe ser la implementación usada por `ServerNode` en ejecución normal.

`NoOpPeerTransport` solo debe quedar como fallback o prueba local.

---

## Clase `PeerListener`

### Responsabilidad

Escuchar conexiones desde otros nodos usando el puerto inter-nodo.

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

`PeerListener` no debe procesar el mensaje completo en el hilo de aceptación.

Solo debe aceptar la conexión y delegar al `peerWorkerPool`.

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

- debe usar `NodeInfo.host` y `NodeInfo.peerPort`;
- debe manejar excepciones de conexión;
- debe registrar error si un peer no responde;
- no debe matar el proceso completo si falla un peer;
- debe permitir que Persona 5 conecte lógica de fallos después;
- debe usar `peerSocketTimeoutMs` cuando corresponda.

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

### Métodos mínimos esperados

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

- no agregar `self` como peer remoto;
- no duplicar nodos por IP/puerto si tienen el mismo `nodeId`;
- actualizar `lastSeenMillis` cuando se recibe un mensaje válido de un nodo;
- registrar logs cuando aparece un nuevo peer;
- marcar como `SUSPECTED` si falla una conexión inicial;
- no detener el nodo completo si un peer no está disponible.

---

## Clase `MembershipUpdateMessage`

`MembershipUpdateMessage` puede ser creada por Persona 2 si decide propagar cambios de membresía además de `PEER_HELLO` y `PEER_HELLO_ACK`.

Estructura sugerida:

~~~java
public class MembershipUpdateMessage extends NodeMessage {
    private final List<NodeInfo> nodes;
    private final String reason;
}
~~~

Uso esperado:

- compartir nodos conocidos;
- informar cambios básicos de estado;
- preparar integración con Persona 5.

No es obligatorio implementar una membresía compleja en esta etapa. Basta con dejarla preparada o con una implementación básica.

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

## Flujo mínimo esperado de comunicación inter-nodo

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
| Timeout de socket | Log + marcar peer como `SUSPECTED` |
| Error leyendo objeto | Log + cerrar socket |
| Error escribiendo objeto | Log + marcar peer como `SUSPECTED` |

---

## Relación con el listener de clientes

Persona 2 debe concentrarse en la capa inter-nodo.

Debe preservar o dejar preparado el listener de clientes mediante `clientWorkerPool`, pero no necesita implementar toda la lógica final de comandos de clientes.

La integración completa de:

- login;
- logout;
- mensaje privado;
- mensaje grupal;
- creación de grupo;
- unión a grupo;

corresponde principalmente a Persona 3.

---

## Reglas de implementación para no bloquear tareas críticas

Persona 2 debe respetar la separación de pools:

| Responsabilidad | Pool correcto |
|---|---|
| Clientes locales | `clientWorkerPool` |
| Mensajes entre nodos | `peerWorkerPool` |
| Heartbeats/timeouts | `schedulerPool` |
| Coordinación distribuida | `coordinationExecutor` |

No debe usar `clientWorkerPool` para mensajes inter-nodo.

No debe usar `clientWorkerPool` para heartbeats.

No debe usar `clientWorkerPool` para coordinación distribuida.

---

## Criterios de aceptación de Persona 2

La tarea de Persona 2 se considera terminada cuando:

1. Existe una implementación TCP real de `PeerTransport`.
2. `NoOpPeerTransport` ya no se usa como transporte principal en ejecución normal.
3. Existe `PeerListener` escuchando en `peerPort`.
4. Existe `PeerConnectionManager` capaz de enviar un `NodeMessage` serializado a otro nodo.
5. Existe `PeerMessageHandler` capaz de recibir y despachar un `NodeMessage`.
6. Cada nodo puede iniciarse desde su archivo `.properties`.
7. Cada nodo carga correctamente su lista de peers.
8. Cada nodo inicializa `clientWorkerPool`.
9. Cada nodo inicializa `peerWorkerPool`.
10. Cada nodo inicializa `schedulerPool`.
11. Cada nodo inicializa `coordinationExecutor`.
12. Cada nodo puede enviar `PEER_HELLO`.
13. Cada nodo puede responder `PEER_HELLO_ACK`.
14. Cada nodo mantiene y actualiza `MembershipManager`.
15. Los logs muestran peers detectados.
16. Si un peer no está disponible, el nodo no se cae completo.
17. No se usa `new Thread(...)` ilimitado por cliente o peer.
18. El código queda preparado para que Persona 3 use el transporte inter-nodo.
19. El proyecto compila.
20. La ejecución de tres nodos en paralelo no depende de un broker central.

---

## Qué debe entregar Persona 2

Persona 2 debe entregar:

- código fuente de las clases creadas o modificadas;
- implementación TCP real de `PeerTransport`;
- `PeerListener`;
- `PeerConnectionManager`;
- `PeerMessageHandler`;
- `MembershipUpdateMessage`, si se implementa;
- logs de ejecución de tres nodos;
- breve README técnico para ejecutar los nodos;
- explicación de cómo se inicializan los pools;
- explicación de cómo se registra un peer;
- explicación de qué errores básicos maneja;
- evidencia de que `NoOpPeerTransport` fue reemplazado en ejecución normal.

---

## README técnico mínimo esperado

Persona 2 debe dejar algo como:

~~~md
# Ejecución de nodos

## Compilar

mvn clean package

## Ejecutar node1

mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node1.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java

## Ejecutar node2

mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node2.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java

## Ejecutar node3

mvn -Dexec.mainClass=whatsapp.server.core.ServerNode -Dexec.args="config/node3.properties" org.codehaus.mojo:exec-maven-plugin:3.5.1:java

## Resultado esperado

Cada nodo debe mostrar por consola los peers detectados.
~~~

---

## Dependencias con otras personas

### Persona 3

Usará:

- `ServerNode`;
- `PeerTransport`;
- `PeerConnectionManager`;
- `MembershipManager`;
- `NodeMessage`;
- `NodeMessageType`;
- `MessageRouter`.

Necesita que Persona 2 deje funcionando el envío de mensajes entre nodos.

---

### Persona 4

Usará:

- `NodeMessage.lamportTimestamp`;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- `coordinationExecutor`.

Necesita que Persona 2 deje la estructura preparada para transportar mensajes de coordinación.

---

### Persona 5

Usará:

- `MembershipManager`;
- `NodeStatus`;
- `HEARTBEAT`;
- `HEARTBEAT_ACK`;
- `schedulerPool`.

Necesita que Persona 2 deje la base para detectar y marcar nodos.

---

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
11. No reescribir desde cero clases ya entregadas por Persona 1 sin necesidad.
12. No cambiar el formato de configuración sin actualizar `NodeConfig` y documentación.
13. No dejar `NoOpPeerTransport` como transporte principal final.

---

## Resultado esperado final de Persona 2

Al terminar Persona 2, el sistema debe haber pasado de:

~~~text
Tres ServerNode que solo cargan configuración y usan NoOpPeerTransport
~~~

a:

~~~text
Tres ServerNode independientes capaces de descubrirse y comunicarse entre sí por TCP
~~~

La entrega de Persona 2 no necesita tener todavía chat privado distribuido completo, chat grupal distribuido completo ni fallos completos, pero sí debe dejar la infraestructura necesaria para que esas funcionalidades puedan implementarse encima.

El resultado mínimo defendible es:

~~~text
node1 inicia en peerPort 6001
node2 inicia en peerPort 6002
node3 inicia en peerPort 6003

node1 envía PEER_HELLO a node2 y node3
node2 responde PEER_HELLO_ACK
node3 responde PEER_HELLO_ACK

MembershipManager queda actualizado
Los logs muestran peers detectados
El sistema no depende de un broker central
~~~