# Prototipo Distribuido de Mensajería Instantánea Inspirado en WhatsApp

Proyecto final de la asignatura **Computación Paralela y Distribuida - ICI4344-1**.

Este repositorio contiene un prototipo distribuido de mensajería instantánea desarrollado en **Java**, inspirado en WhatsApp. La entrega final evoluciona desde una arquitectura cliente-servidor centralizada hacia una arquitectura **multiservidor distribuida**, compuesta por tres nodos independientes (`node1`, `node2` y `node3`) que se comunican entre sí mediante **sockets TCP** y serialización de objetos Java.

El sistema permite mensajería privada y mensajería grupal entre usuarios conectados a distintos nodos, registra eventos con relojes lógicos de **Lamport**, coordina el acceso al registro distribuido de grupos mediante **Ricart-Agrawala**, elige coordinador mediante **Bully** y detecta fallos por **heartbeats** y **timeouts**.

---

## Integrantes

- Felipe Astudillo
- Ian Guerrero
- Francisca Guzmán
- Benjamín Leiva
- Ignacio Reyes
- Benjamín Soto

---

## Resumen técnico

El sistema se ejecuta como una red de tres procesos Java independientes. Cada `ServerNode` cumple simultáneamente dos roles:

1. **Servidor de clientes locales**, aceptando conexiones de usuarios mediante sockets TCP.
2. **Peer distribuido**, comunicándose con los demás nodos servidores mediante mensajes inter-nodo.

La comunicación cliente-servidor utiliza objetos serializables derivados de `PaqueteRed`, mientras que la comunicación entre nodos utiliza objetos serializables derivados de `NodeMessage`.

Las dos funciones principales son:

- **Mensajería privada distribuida:** un usuario puede enviar mensajes uno a uno a otro usuario, aunque ambos estén conectados a nodos distintos.
- **Mensajería grupal distribuida:** los usuarios pueden crear grupos, unirse a grupos existentes y enviar mensajes a grupos con miembros repartidos entre distintos nodos.

---

## Conceptos distribuidos implementados

| Concepto | Implementación en el proyecto |
|---|---|
| Topología multinodo | Tres `ServerNode` independientes: `node1`, `node2`, `node3`. |
| Comunicación remota | Sockets TCP entre clientes y nodos, y entre nodos servidores. |
| Marshalling | Serialización nativa de objetos Java con `ObjectInputStream` y `ObjectOutputStream`. |
| Transparencia de acceso | El cliente usa los mismos comandos sin importar el nodo al que se conecte. |
| Transparencia de ubicación | El remitente usa identificadores lógicos de usuario o grupo; el sistema resuelve el nodo destino. |
| Ausencia de reloj global | Los eventos se ordenan con relojes lógicos de Lamport. |
| Coordinación distribuida | Ricart-Agrawala para proteger `GROUP_REGISTRY`; Bully para elección de coordinador. |
| Tolerancia a fallos | Heartbeats, timeouts, detección de nodos caídos y reelección automática. |
| Concurrencia | Pools de hilos separados para clientes, peers, scheduler y coordinación. |
| Prueba de carga | Generador Java con 50 clientes concurrentes por al menos 60 segundos. |

---

## Arquitectura general

```text
              ┌──────────────────────┐
              │      Cliente A       │
              └──────────┬───────────┘
                         │ TCP cliente
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                         node1                               │
│ clientPort=5001                         peerPort=6001       │
│ ServerNode + MessageRouter + Lamport + R-A + Bully          │
└───────────────┬───────────────────────────────┬─────────────┘
                │ TCP peer                       │ TCP peer
                ▼                                ▼
┌─────────────────────────────┐     ┌─────────────────────────────┐
│            node2            │     │            node3            │
│ clientPort=5002             │     │ clientPort=5003             │
│ peerPort=6002               │     │ peerPort=6003               │
└─────────────────────────────┘     └─────────────────────────────┘
        ▲                                      ▲
        │ TCP cliente                          │ TCP cliente
┌───────┴────────┐                    ┌────────┴───────┐
│   Cliente B    │                    │   Cliente C    │
└────────────────┘                    └────────────────┘
```

Cada nodo mantiene:

- sesiones locales de clientes conectados;
- vista del directorio global de usuarios;
- vista del registro distribuido de grupos;
- lista de membresía de nodos;
- reloj lógico de Lamport;
- logger de eventos distribuidos;
- coordinador Ricart-Agrawala;
- coordinador Bully;
- transporte TCP entre peers.

---

## Estructura del repositorio

```text
Aplicacion-WhatsApp-main/
├── config/
│   ├── node1.properties
│   ├── node2.properties
│   └── node3.properties
│
├── docs/
│   ├── arquitectura-final.md
│   ├── contrato-mensajes-nodos.md
│   ├── matriz-evolucion-inicial-final.md
│   ├── adr-001-multiservidor.md
│   ├── adr-002-thread-pool-por-nodo.md
│   └── diagrams-entrega-2/
│       ├── modelo-fisico-final.puml/.png/.svg
│       ├── arquitectura-final.puml/.png/.svg
│       ├── seq-mensaje-privado-distribuido.puml/.png/.svg
│       ├── seq-mensaje-grupal-distribuido.puml/.png/.svg
│       ├── sq-ricart-agrawala-group-registrt.puml/.png/.svg
│       └── seq-heartbeat-caida-reeleccion.puml/.png/.svg
│
├── evidencia-manual/
│   └── logs de una corrida manual de demostración
│
├── loadtest-results/
│   ├── loadtest_<timestamp>.csv
│   ├── coordination-analysis.txt
│   └── charts/
│       ├── throughput.png
│       ├── latencia.png
│       └── errores.png
│
├── logs/
│   ├── events-node1.log
│   ├── events-node2.log
│   ├── events-node3.log
│   ├── loadgenerator.log
│   ├── node1-console.log
│   ├── node2-console.log
│   └── node3-console.log
│
├── scripts/
│   ├── clean-evidence.ps1
│   ├── start-nodes.ps1
│   ├── start-clients.ps1
│   ├── start-demo.ps1
│   ├── run-loadtest.ps1
│   ├── generate-charts.ps1
│   └── analyze-coordination.ps1
│
├── src/main/java/whatsapp/
│   ├── client/
│   │   └── ClienteNodo.java
│   ├── common/models/
│   │   ├── PaqueteRed.java
│   │   ├── PaqueteLogin.java
│   │   ├── PaqueteMensaje.java
│   │   ├── PaqueteCrearGrupo.java
│   │   ├── PaqueteUnirseGrupo.java
│   │   ├── PaqueteConfirm.java
│   │   └── PaqueteError.java
│   ├── loadtest/
│   │   ├── LoadGenerator.java
│   │   ├── VirtualClient.java
│   │   ├── MetricsRecorder.java
│   │   ├── MetricsChartGenerator.java
│   │   └── CoordinationLogAnalyzer.java
│   └── server/
│       ├── clock/
│       ├── config/
│       ├── core/
│       ├── directory/
│       ├── election/
│       ├── handlers/
│       ├── managers/
│       ├── membership/
│       ├── messages/
│       ├── mutex/
│       ├── node/
│       ├── peer/
│       └── routing/
│
├── pom.xml
└── test.sh
```

---

## Requisitos

- **Java 17 o superior**.
- **Maven** instalado y disponible en el `PATH`, o Maven integrado en NetBeans.
- Windows PowerShell para usar los scripts `.ps1` incluidos.
- Puertos libres:
  - clientes: `5001`, `5002`, `5003`;
  - peers: `6001`, `6002`, `6003`.

El proyecto no usa dependencias externas adicionales en `pom.xml`; se apoya en bibliotecas estándar de Java.

---

## Compilación

Desde la raíz del proyecto:

```powershell
mvn clean package -DskipTests
```

Al finalizar, Maven genera el JAR en:

```text
target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar
```

Si se trabaja desde NetBeans, también puede usarse la opción **Clean and Build**.

---

## Configuración de nodos

Los nodos se configuran en la carpeta `config/`.

### `node1`

```properties
node.id=node1
node.host=localhost
node.clientPort=5001
node.peerPort=6001
node.peers=node2@localhost:5002:6002,node3@localhost:5003:6003
```

### `node2`

```properties
node.id=node2
node.host=localhost
node.clientPort=5002
node.peerPort=6002
node.peers=node1@localhost:5001:6001,node3@localhost:5003:6003
```

### `node3`

```properties
node.id=node3
node.host=localhost
node.clientPort=5003
node.peerPort=6003
node.peers=node1@localhost:5001:6001,node2@localhost:5002:6002
```

Parámetros comunes relevantes:

```properties
pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1
socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000
heartbeat.intervalMs=2000
heartbeat.timeoutMs=10000
```

---

## Ejecución rápida con scripts

### 1. Limpiar evidencia anterior

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\clean-evidence.ps1
```

Esto elimina logs y resultados previos, y recrea las carpetas `logs/` y `loadtest-results/charts/`.

### 2. Iniciar los tres nodos

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-nodes.ps1
```

El script abre tres ventanas de PowerShell y ejecuta:

- `node3` con `config/node3.properties`;
- `node2` con `config/node2.properties`;
- `node1` con `config/node1.properties`.

Se inicia primero `node3`, luego `node2` y finalmente `node1` para reducir mensajes iniciales de `Connection refused` durante el descubrimiento.

### 3. Iniciar clientes manuales de demostración

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-clients.ps1
```

Esto abre tres clientes conectados a los tres nodos:

- Cliente A → `localhost:5001` (`node1`)
- Cliente B → `localhost:5002` (`node2`)
- Cliente C → `localhost:5003` (`node3`)

### 4. Levantar demo completa

También puede iniciarse todo con:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-demo.ps1
```

Este script inicia los nodos, espera unos segundos y luego abre los clientes.

---

## Ejecución manual sin scripts

Abrir tres terminales distintas desde la raíz del proyecto.

### Terminal 1

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.server.core.ServerNode config\node1.properties
```

### Terminal 2

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.server.core.ServerNode config\node2.properties
```

### Terminal 3

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.server.core.ServerNode config\node3.properties
```

Luego, para iniciar un cliente:

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.client.ClienteNodo localhost 5001
```

Se puede cambiar el puerto para conectar el cliente a otro nodo:

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.client.ClienteNodo localhost 5002
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.client.ClienteNodo localhost 5003
```

---

## Comandos del cliente

Una vez conectado, el cliente acepta los siguientes comandos:

```text
/login <nombre_usuario>             Iniciar sesión
/msg <destinatario> <mensaje>       Enviar mensaje privado
/creargrupo <id_grupo>              Crear un grupo nuevo
/unirse <id_grupo>                  Unirse a un grupo existente
/gmsg <id_grupo> <mensaje>          Enviar mensaje grupal
/salir                              Desconectarse
/?                                  Listar comandos disponibles
```

### Ejemplo de prueba manual

Cliente conectado a `node1`:

```text
/login felipe
/creargrupo amigos
/gmsg amigos Hola desde node1
```

Cliente conectado a `node2`:

```text
/login luis
/unirse amigos
/msg felipe Hola Felipe, soy Luis desde node2
```

Cliente conectado a `node3`:

```text
/login maria
/unirse amigos
/gmsg amigos Hola a todos, soy Maria desde node3
```

El usuario no necesita conocer en qué nodo está conectado el destinatario. El sistema resuelve la ubicación mediante el directorio global y enruta el mensaje al nodo correspondiente.

---

## Comportamiento esperado al iniciar los nodos

Durante el arranque, cada nodo debe mostrar mensajes similares a:

```text
[nodeX] Iniciando ServerNode
[nodeX] clientPort=500X peerPort=600X
[nodeX] PeerListener escuchando en puerto 600X
[nodeX][PeerConnMgr] Enviando PEER_HELLO a nodeY
[nodeX][PeerConnMgr] PEER_HELLO_ACK recibido desde nodeY
[nodeX][PeerConnMgr] Peer detectado: nodeY
[nodeX] Emisor de Heartbeats programado cada 2000ms
[nodeX] Sweeper de fallos programado. Tolerancia máxima: 10000ms
[nodeX] ServerNode iniciado — Ricart-Agrawala y Bully activos.
```

Mensajes clave para verificar comunicación distribuida:

```text
PEER_HELLO enviado
PEER_HELLO_ACK recibido
Peer detectado
HEARTBEAT
SEND APP
RECEIVE APP
```

---

## Relojes de Lamport y EventLogger

Cada nodo mantiene un `LamportClock` propio. El reloj lógico se actualiza en eventos locales, envíos y recepciones de mensajes inter-nodo.

Los eventos relevantes son registrados por `EventLogger` con marcas lógicas. Al detener un nodo de forma limpia, se escriben archivos como:

```text
logs/events-node1.log
logs/events-node2.log
logs/events-node3.log
```

Estos logs permiten revisar:

- eventos locales;
- envíos y recepciones inter-nodo;
- mensajes `APP`;
- mensajes `MUTEX_REQUEST` y `MUTEX_REPLY`;
- mensajes `ELECTION`, `ELECTION_OK` y `ELECTION_COORDINATOR`;
- detección de fallos;
- marcas lógicas `L=<valor>`.

---

## Coordinación distribuida

### Ricart-Agrawala

El sistema implementa Ricart-Agrawala para proteger el recurso crítico lógico:

```text
GROUP_REGISTRY
```

Este recurso corresponde al registro distribuido de grupos. Se usa especialmente en operaciones de creación y modificación de grupos, donde varios nodos podrían intentar actualizar el mismo estado lógico.

Mensajes esperados:

```text
MUTEX_REQUEST
MUTEX_REPLY
```

Estados esperados:

```text
RELEASED
WANTED
HELD
DEFERIDO
```

Durante el arranque, los nodos ejecutan una demostración automática intentando crear el grupo:

```text
grupo-demo-ricart
```

Solo un nodo debe crearlo exitosamente; los demás deben detectar que el grupo ya existe. Esto demuestra acceso secuencial al recurso crítico.

### Bully

El sistema implementa Bully para elegir coordinador. En condiciones normales, el nodo con mayor identificador lógico gana la elección. Con los nombres actuales, el coordinador esperado inicialmente es:

```text
node3
```

Si el coordinador cae, los nodos restantes lo detectan mediante heartbeats y timeouts, e inician una nueva elección. Si cae `node3`, el nuevo coordinador esperado es:

```text
node2
```

Mensajes esperados:

```text
ELECTION
ELECTION_OK
ELECTION_COORDINATOR
```

---

## Tolerancia a fallos

Cada nodo ejecuta dos tareas periódicas:

- `HeartbeatEmitterTask`: envía heartbeats a los peers vivos.
- `HeartbeatSweeperTask`: revisa la última actividad conocida y marca nodos como caídos cuando superan el timeout.

Configuración por defecto:

```properties
heartbeat.intervalMs=2000
heartbeat.timeoutMs=10000
```

Ante la caída de un nodo:

1. Los demás nodos dejan de recibir heartbeats.
2. El sweeper marca el nodo como `DOWN`.
3. Se limpia la información asociada al nodo caído en el directorio global.
4. Si el nodo caído era coordinador, Bully dispara una nueva elección.
5. Los clientes sintéticos del generador de carga intentan reconectarse a otro nodo disponible.

El sistema tolera fallos de tipo crash y omisión parcial de mensajes. No implementa tolerancia bizantina.

---

## Prueba de carga

El proyecto incluye un generador de carga en Java:

```text
whatsapp.loadtest.LoadGenerator
```

Por defecto ejecuta:

- 50 clientes concurrentes;
- 65 segundos de duración;
- distribución de clientes entre `node1`, `node2` y `node3`;
- mensajería privada;
- mensajería grupal;
- creación de grupos para ejercitar `GROUP_REGISTRY`;
- reconexión automática de clientes sintéticos si cae el nodo al que estaban conectados.

### Ejecutar prueba de carga con script

Con los tres nodos ya levantados:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-loadtest.ps1
```

Durante la corrida, el script indica:

```text
Durante la corrida: mata node3 alrededor del segundo 30 y presiona ENTER aquí para marcar la falla.
```

Procedimiento recomendado:

1. Iniciar los tres nodos.
2. Ejecutar `run-loadtest.ps1`.
3. Esperar aproximadamente 30 segundos.
4. Cerrar la ventana de `node3` o interrumpirla con `Ctrl+C`.
5. Volver a la ventana del generador de carga y presionar `ENTER` para marcar el momento de falla.
6. Esperar a que la prueba termine.

### Ejecutar prueba de carga manualmente

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.loadtest.LoadGenerator 50 65
```

También se pueden cambiar los parámetros:

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.loadtest.LoadGenerator <clientes> <segundos>
```

Ejemplo:

```powershell
java -cp target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.loadtest.LoadGenerator 80 90
```

---

## Métricas recolectadas

El generador registra cada operación en un CSV dentro de:

```text
loadtest-results/loadtest_<timestamp>.csv
```

Columnas del CSV:

```text
epochMillis,offsetMs,clientId,opType,latencyMs,success,errorDetail
```

Métricas calculadas:

- throughput en requests por segundo;
- latencia promedio;
- latencia p95;
- tasa de error;
- segmentación por ventana:
  - normal, sin falla;
  - con caída del coordinador;
  - después de recuperación.

---

## Generación de gráficos

Después de ejecutar la prueba de carga:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-charts.ps1
```

El script toma el CSV más reciente de `loadtest-results/` y genera:

```text
loadtest-results/charts/throughput.png
loadtest-results/charts/latencia.png
loadtest-results/charts/errores.png
```

Estos gráficos corresponden a:

- throughput por segundo;
- latencia promedio y p95 en el tiempo;
- tasa de error por segundo.

---

## Análisis de mensajes de coordinación

Para contar los mensajes generados por los algoritmos de coordinación:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\analyze-coordination.ps1
```

El script analiza preferentemente:

```text
logs/events-node1.log
logs/events-node2.log
logs/events-node3.log
```

Si esos archivos aún no existen, usa como respaldo:

```text
logs/node1-console.log
logs/node2-console.log
logs/node3-console.log
```

La salida queda en:

```text
loadtest-results/coordination-analysis.txt
```

El analizador cuenta eventos `SEND` de:

```text
MUTEX_REQUEST
MUTEX_REPLY
ELECTION
ELECTION_OK
ELECTION_COORDINATOR
```

---

## Evidencia esperada para la entrega

Después de una corrida completa, deberían existir:

```text
logs/
├── events-node1.log
├── events-node2.log
├── events-node3.log
├── loadgenerator.log
├── node1-console.log
├── node2-console.log
└── node3-console.log

loadtest-results/
├── loadtest_<timestamp>.csv
├── coordination-analysis.txt
└── charts/
    ├── throughput.png
    ├── latencia.png
    └── errores.png
```

Los logs `events-nodeX.log` son la evidencia principal del ordenamiento lógico con Lamport y de los eventos distribuidos. Los archivos de consola permiten respaldar el comportamiento observado durante la demo.

---

## Ejecución alternativa en Linux o Git Bash

El repositorio incluye `test.sh`, que levanta nodos, ejecuta clientes con comandos predefinidos y luego detiene los procesos:

```bash
chmod +x test.sh
./test.sh
```

Este script es útil para una prueba rápida, pero para la entrega final se recomienda usar los scripts de PowerShell, porque guardan evidencia en `logs/` y separan mejor la demo manual, la carga, los gráficos y el análisis de coordinación.

---

## Principales clases del sistema

| Clase | Responsabilidad |
|---|---|
| `whatsapp.server.core.ServerNode` | Nodo principal de la arquitectura multiservidor. |
| `whatsapp.server.config.NodeConfig` | Carga configuración desde archivos `.properties`. |
| `whatsapp.server.peer.TcpPeerTransport` | Transporte TCP para mensajes entre nodos. |
| `whatsapp.server.peer.PeerListener` | Listener de conexiones entrantes entre peers. |
| `whatsapp.server.peer.PeerMessageHandler` | Procesa mensajes inter-nodo recibidos. |
| `whatsapp.server.peer.HeartbeatEmitterTask` | Envía heartbeats periódicos. |
| `whatsapp.server.peer.HeartbeatSweeperTask` | Detecta nodos inactivos o caídos. |
| `whatsapp.server.membership.MembershipManager` | Mantiene la vista de membresía de nodos. |
| `whatsapp.server.directory.GlobalUserDirectory` | Resuelve en qué nodo está conectado cada usuario. |
| `whatsapp.server.managers.LocalSessionManager` | Administra sesiones locales de clientes. |
| `whatsapp.server.managers.DistributedGroupManager` | Administra grupos distribuidos y usa Ricart-Agrawala. |
| `whatsapp.server.routing.MessageRouter` | Enruta mensajes privados y grupales entre nodos. |
| `whatsapp.server.clock.LamportClock` | Implementa el reloj lógico de Lamport. |
| `whatsapp.server.clock.EventLogger` | Registra eventos ordenables por marca lógica. |
| `whatsapp.server.mutex.RicartAgrawalaCoordinator` | Coordina exclusión mutua distribuida. |
| `whatsapp.server.election.BullyElectionCoordinator` | Ejecuta elección de coordinador con Bully. |
| `whatsapp.client.ClienteNodo` | Cliente interactivo de consola. |
| `whatsapp.loadtest.LoadGenerator` | Generador de carga concurrente. |
| `whatsapp.loadtest.VirtualClient` | Cliente sintético usado por la prueba de carga. |
| `whatsapp.loadtest.MetricsRecorder` | Registra métricas y genera CSV. |
| `whatsapp.loadtest.MetricsChartGenerator` | Genera gráficos desde el CSV. |
| `whatsapp.loadtest.CoordinationLogAnalyzer` | Cuenta mensajes de coordinación en logs. |

---

## Tipos de mensajes inter-nodo

El sistema utiliza mensajes derivados de `NodeMessage`, entre ellos:

```text
PEER_HELLO
PEER_HELLO_ACK
HEARTBEAT
APP
USER_LOGIN_ANNOUNCE
USER_LOGOUT_ANNOUNCE
MEMBERSHIP_UPDATE
MUTEX_REQUEST
MUTEX_REPLY
ELECTION
ELECTION_OK
ELECTION_COORDINATOR
```

Estos mensajes permiten descubrimiento de peers, propagación de sesiones, enrutamiento de mensajes de aplicación, coordinación distribuida, elección de coordinador y detección de fallos.

---

## Limitaciones conocidas

Este proyecto es un prototipo académico, por lo que existen limitaciones intencionales:

- No hay cifrado TLS en los sockets.
- No hay autenticación criptográfica entre nodos.
- La serialización Java se usa como mecanismo de marshalling, pero no se endurece con filtros de deserialización estrictos en esta versión.
- El estado de usuarios y grupos se mantiene en memoria; no hay persistencia en base de datos.
- Se toleran fallos crash y omisión parcial, pero no fallos bizantinos.
- La recuperación se orienta a mantener el servicio distribuido operativo; no garantiza entrega exactamente una vez.
- Los archivos `events-nodeX.log` se escriben al detener ordenadamente cada nodo mediante el shutdown hook.

---

## Flujo recomendado para la demo final

1. Compilar el proyecto:

```powershell
mvn clean package -DskipTests
```

2. Limpiar evidencia previa:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\clean-evidence.ps1
```

3. Levantar los nodos:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-nodes.ps1
```

4. Esperar a que se observen:

```text
PEER_HELLO_ACK recibido
COORDINADOR ELECTO
DEMO R-A
```

5. Levantar clientes manuales:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-clients.ps1
```

6. Demostrar mensajería privada y grupal con usuarios conectados a nodos distintos.

7. Ejecutar carga:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-loadtest.ps1
```

8. Durante la carga, derribar `node3` y presionar `ENTER` en el generador para marcar la falla.

9. Esperar reelección de coordinador y continuidad parcial del servicio.

10. Generar gráficos:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-charts.ps1
```

11. Analizar coordinación:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\analyze-coordination.ps1
```

12. Adjuntar `logs/`, `loadtest-results/`, informe, código fuente y presentación.

---

## Solución de problemas frecuentes

### `Address already in use`

Algún puerto sigue ocupado por una ejecución anterior. Cerrar las ventanas de nodos o terminar procesos Java activos.

En PowerShell:

```powershell
Get-Process java -ErrorAction SilentlyContinue
Stop-Process -Name java -Force
```

### `Connection refused` al iniciar

Puede aparecer si un nodo intenta conectarse a otro peer que todavía no arrancó. Si luego aparecen `PEER_HELLO_ACK recibido` y `Peer detectado`, no es un problema crítico. Para reducirlo, iniciar primero `node3`, luego `node2` y finalmente `node1`, como hace `start-nodes.ps1`.

### No se generan `events-nodeX.log`

Los eventos se escriben al detener el nodo. Cerrar la ventana con `Ctrl+C` o detener ordenadamente el proceso para activar el shutdown hook.

### El generador de carga muestra muchos errores de conexión

Verificar que los tres nodos estén levantados antes de ejecutar la prueba. Si se está probando la falla inducida, es normal observar aumento temporal de errores y latencia durante la ventana de caída y reelección.

### Maven no se reconoce como comando

Instalar Maven o usar el Maven integrado en NetBeans. Desde NetBeans, usar **Clean and Build**.

---

## Comandos esenciales

```powershell
# Compilar
-mvn clean package -DskipTests

# Limpiar evidencia
powershell -ExecutionPolicy Bypass -File .\scripts\clean-evidence.ps1

# Iniciar nodos
powershell -ExecutionPolicy Bypass -File .\scripts\start-nodes.ps1

# Iniciar clientes
powershell -ExecutionPolicy Bypass -File .\scripts\start-clients.ps1

# Ejecutar carga 50 clientes / 65 segundos (generador de carga)
powershell -ExecutionPolicy Bypass -File .\scripts\run-loadtest.ps1

# Generar gráficos
powershell -ExecutionPolicy Bypass -File .\scripts\generate-charts.ps1

# Analizar mensajes de coordinación
powershell -ExecutionPolicy Bypass -File .\scripts\analyze-coordination.ps1
```

---

## Estado final del proyecto

El proyecto entrega un prototipo funcional de mensajería distribuida con tres nodos Java independientes, comunicación real por sockets TCP, serialización de objetos, relojes lógicos de Lamport, coordinación distribuida mediante Ricart-Agrawala y Bully, detección de fallos por heartbeats, prueba de carga concurrente y evidencia en logs, métricas y gráficos.
