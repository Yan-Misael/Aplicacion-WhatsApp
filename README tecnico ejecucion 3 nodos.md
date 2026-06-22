# README Técnico – Ejecución de 3 nodos

## Requisitos

* Java 17 o superior (probado con Java 26).
* Maven instalado o el Maven incluido con NetBeans.

---

## Compilación del proyecto

Ubicarse en la carpeta raíz del proyecto y ejecutar:

```powershell
cd Aplicacion-WhatsApp-main
mvn package -DskipTests
```

Si Maven no se encuentra configurado en el PATH, puede utilizarse el ejecutable incluido con NetBeans:

```powershell
& "C:\Users\TuUsuario\Downloads\netbeans\java\maven\bin\mvn.cmd" package -DskipTests
```

La compilación debe finalizar con:

```text
BUILD SUCCESS
```

El archivo generado quedará en:

```text
target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar
```

---

## Configuración de los nodos

El sistema incluye tres archivos de configuración:

* `config/node1.properties`
* `config/node2.properties`
* `config/node3.properties`

Cada archivo define los puertos utilizados por cada servidor para la comunicación con clientes y con los demás nodos.

---

## Ejecución de los tres nodos

Desde PowerShell, ubicado en la raíz del proyecto, ejecutar:

```powershell
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$PWD'; java -cp 'target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node1.properties"

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$PWD'; java -cp 'target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node2.properties"

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$PWD'; java -cp 'target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node3.properties"
```

Se abrirán tres ventanas independientes de PowerShell, cada una correspondiente a un nodo del sistema.

---

## Verificación del arranque

Los nodos pueden iniciarse simultáneamente o en momentos distintos. Una vez que todos estén activos, deben detectarse mutuamente mediante el intercambio de mensajes `PEER_HELLO` y `PEER_HELLO_ACK`.

### Ejemplo de logs del primer nodo en iniciar (`node1`)

```text
[node1] Iniciando ServerNode
[node1] clientPort=5001 peerPort=6001
[node1] clientWorkerPool=64 peerWorkerPool=16 schedulerPool=4 coordinationExecutor=1
[node1] Peers configurados:
[node1]  - node2@localhost:5002:6002 [ALIVE]
[node1]  - node3@localhost:5003:6003 [ALIVE]
[node1][TcpPeerTransport] Iniciando TcpPeerTransport en puerto 6001
[node1][PeerListener] PeerListener escuchando en puerto 6001
[node1][PeerConnMgr] Enviando PEER_HELLO a node2 localhost:6002
[node1][PeerConnMgr] Enviando PEER_HELLO a node3 localhost:6003
[node1][TcpPeerTransport] TcpPeerTransport iniciado
[node1] Emisor de Heartbeats programado cada 2000ms
[node1] Sweeper de fallos programado. Tolerancia máxima de inactividad: 6000ms
[node1] ServerNode iniciado — Ricart-Agrawala y Bully activos.
[node1][PeerConnMgr] PEER_HELLO enviado a node2
[node1][PeerConnMgr] PEER_HELLO enviado a node3
[node1][PeerConnMgr] PEER_HELLO_ACK recibido desde node2
[node1][PeerConnMgr] Peer detectado: node2
[node1][PeerConnMgr] PEER_HELLO_ACK recibido desde node3
[node1][PeerConnMgr] Peer detectado: node3
```

### Ejemplo de logs del último nodo en iniciar (`node3`)

```text
[node3] Iniciando ServerNode
[node3][PeerListener] PeerListener escuchando en puerto 6003
[node3][PeerConnMgr] PEER_HELLO_ACK recibido desde node1
[node3][PeerConnMgr] Peer detectado: node1
[node3][PeerConnMgr] PEER_HELLO_ACK recibido desde node2
[node3][PeerConnMgr] Peer detectado: node2
```

La aparición de mensajes como:

* `PEER_HELLO enviado`
* `PEER_HELLO_ACK recibido`
* `Peer detectado`

indica que la comunicación TCP entre los nodos se estableció correctamente, incluso si los servidores fueron iniciados en distintos momentos.

---

## Resultado esperado

### 1. Descubrimiento TCP

Una vez ejecutados los tres procesos, cada nodo mantiene comunicación TCP con los demás y se detectan mutuamente mediante `PEER_HELLO` / `PEER_HELLO_ACK`.

### 2. Elección de coordinador — algoritmo Bully (~4 segundos)

Aproximadamente 4 segundos después del arranque, los nodos realizan una elección automática para determinar el coordinador. El nodo con mayor identificador lexicográfico gana (`node3 > node2 > node1`).

Todos los nodos deben mostrar:

```text
[nodeX][Bully] === COORDINADOR ELECTO: node3 ===
```

Si un nodo cae (por ejemplo, cerrando su ventana con Ctrl+C), los demás lo detectan en aproximadamente 6 segundos (timeout de heartbeat) e inician una nueva elección automáticamente:

```text
[node2] El coordinador node3 cayó. Iniciando elección Bully...
[node2][Bully] === ME PROCLAMO COORDINADOR: node2 ===
[node1][Bully] === COORDINADOR ELECTO: node2 ===
```

### 3. Exclusión mutua distribuida — Ricart-Agrawala (~6 segundos)

Aproximadamente 6 segundos después del arranque, los tres nodos intentan crear simultáneamente el grupo `grupo-demo-ricart` para demostrar la exclusión mutua distribuida. Se puede observar el intercambio de mensajes `MUTEX_REQUEST`, `MUTEX_REPLY` y los estados `DEFERIDO` / `HELD`.

```text
[nodeX][Ricart-Agrawala] ACQUIRE 'GROUP_REGISTRY': REQUEST L=29 → [node1, node3]
[nodeX][Ricart-Agrawala] HELD — todos los replies recibidos
[nodeX][Ricart-Agrawala] RELEASED — respondiendo a 2 diferidos
[nodeX] [DEMO R-A] Grupo 'grupo-demo-ricart' CREADO exitosamente por nodeX
```

El algoritmo garantiza que los nodos acceden a la sección crítica de forma estrictamente secuencial; solo uno tiene el estado `HELD` en cada momento.

### 4. Logs de eventos

Al detener un nodo (Ctrl+C), se genera automáticamente el archivo `logs/events-nodeX.log` con todos los eventos ordenados por timestamp de Lamport, útil para verificar la causalidad de los mensajes distribuidos.
