# ADR-002: Uso de thread-pools acotados por ServerNode

## Contexto

La Entrega Inicial utilizaba un modelo de concurrencia basado en Thread-per-Connection. En ese modelo, cada vez que el servidor aceptaba una conexión de cliente, se creaba un hilo dedicado para atenderlo.

Este enfoque era simple y adecuado para una primera versión del sistema, porque permitía evidenciar que varios clientes podían interactuar simultáneamente con el servidor. Además, facilitaba el aislamiento básico de fallos de clientes: si un cliente se desconectaba, el hilo asociado podía finalizar sin detener necesariamente el servidor completo.

Sin embargo, en la Entrega Final el sistema debe evolucionar hacia una arquitectura multiservidor. Esto significa que cada `ServerNode` no solo atenderá clientes locales, sino que además deberá procesar comunicación con otros nodos, heartbeats, timeouts, coordinación distribuida y recolección de métricas.

Por lo tanto, mantener un modelo Thread-per-Connection ilimitado ya no es una decisión adecuada para la arquitectura final.

## Problema

El modelo Thread-per-Connection presenta varias limitaciones:

1. Crecimiento no acotado de hilos:
   cada cliente conectado puede implicar un nuevo hilo.

2. Riesgo de agotamiento de recursos:
   muchas conexiones simultáneas o inactivas pueden consumir demasiada memoria y CPU.

3. Baja capacidad de control:
   es difícil limitar cuántas tareas se ejecutan simultáneamente.

4. Riesgo ante la prueba de carga:
   la Entrega Final requiere al menos 50 clientes o hilos concurrentes durante 60 segundos, por lo que el servidor debe administrar la concurrencia de forma más controlada.

5. Mezcla peligrosa de responsabilidades:
   si un nodo usa hilos sin planificación clara, las tareas críticas de coordinación o detección de fallos podrían competir de forma desordenada con el tráfico normal de clientes.

6. Dificultad para medir saturación:
   con hilos creados manualmente es más difícil medir cola, tareas activas, rechazos o carga por tipo de responsabilidad.

Además, si se usara un único thread-pool global para todo el nodo, aparecería otro problema: una sobrecarga de solicitudes de clientes podría impedir que se procesen mensajes entre nodos, heartbeats o respuestas de coordinación distribuida.

Por lo tanto, no basta con decir “usar thread-pool”. La arquitectura debe definir pools separados por responsabilidad.

## Decisión

Cada `ServerNode` utilizará thread-pools acotados y separados según responsabilidad.

Se definen los siguientes pools mínimos:

| Pool | Tipo Java sugerido | Responsabilidad principal |
|---|---|---|
| `clientWorkerPool` | `ExecutorService` | Atender conexiones y mensajes de clientes locales |
| `peerWorkerPool` | `ExecutorService` | Procesar mensajes recibidos desde otros nodos |
| `schedulerPool` | `ScheduledExecutorService` | Ejecutar heartbeats, timeouts y métricas periódicas |
| `coordinationExecutor` | `ExecutorService` o `SingleThreadExecutor` | Procesar eventos de coordinación distribuida |

La separación de pools evita que una sobrecarga en una responsabilidad bloquee componentes críticos del sistema.

## Modelo general

Cada `ServerNode` deberá tener una estructura equivalente a:

~~~text
ServerNode
 ├── ClientAcceptor
 │    └── clientWorkerPool
 │         └── ClientConnectionHandler
 │
 ├── PeerListener
 │    └── peerWorkerPool
 │         └── PeerMessageHandler
 │
 ├── schedulerPool
 │    ├── HeartbeatTask
 │    ├── FailureDetectorTask
 │    └── MetricsTask
 │
 ├── coordinationExecutor
 │    └── MutualExclusionManager
 │
 ├── MessageRouter
 ├── LocalSessionManager
 ├── GlobalUserDirectory
 ├── DistributedGroupManager
 ├── MembershipManager
 ├── LamportClock
 └── MetricsCollector
~~~

## Responsabilidades por pool

### clientWorkerPool

Responsable de procesar solicitudes provenientes de clientes locales.

Ejemplos de operaciones:

- login;
- logout;
- envío de mensaje privado;
- creación de grupo solicitada por cliente;
- unión a grupo solicitada por cliente;
- envío de mensaje grupal;
- lectura de comandos del cliente.

Restricciones:

- No debe procesar heartbeats.
- No debe procesar mensajes `MUTEX_REQUEST`.
- No debe procesar mensajes `MUTEX_REPLY`.
- No debe ser usado para tareas periódicas.
- No debe bloquear la comunicación entre nodos.

### peerWorkerPool

Responsable de procesar mensajes entrantes desde otros `ServerNode`.

Ejemplos de mensajes:

- `PEER_HELLO`;
- `MEMBERSHIP_UPDATE`;
- `USER_LOGIN_ANNOUNCE`;
- `USER_LOGOUT_ANNOUNCE`;
- `PRIVATE_MESSAGE_FORWARD`;
- `GROUP_MESSAGE_FORWARD`;
- `GROUP_CREATE_COMMIT`;
- `GROUP_JOIN_COMMIT`;
- `MUTEX_REQUEST`;
- `MUTEX_REPLY`;
- `HEARTBEAT`;
- `HEARTBEAT_ACK`;
- `NODE_ERROR`.

Restricciones:

- No debe ejecutar lógica pesada de clientes.
- No debe quedar bloqueado indefinidamente esperando entrada de un cliente.
- Debe delegar eventos de coordinación al `coordinationExecutor`.

### schedulerPool

Responsable de ejecutar tareas periódicas.

Ejemplos de tareas:

- enviar heartbeats;
- revisar timeouts;
- marcar nodos como `SUSPECTED`;
- marcar nodos como `DOWN`;
- recolectar métricas;
- limpiar sesiones expiradas si corresponde.

Restricciones:

- No debe depender del `clientWorkerPool`.
- No debe bloquearse por tráfico de clientes.
- No debe ejecutar lógica larga de entrega de mensajes.

### coordinationExecutor

Responsable de procesar eventos asociados a coordinación distribuida.

Ejemplos:

- recibir `MUTEX_REQUEST`;
- recibir `MUTEX_REPLY`;
- ordenar solicitudes por timestamp de Lamport;
- decidir si se responde o se difiere una solicitud;
- controlar entrada y salida de la sección crítica;
- registrar eventos de coordinación.

Se recomienda que sea un `SingleThreadExecutor` para simplificar el orden interno de procesamiento de eventos de coordinación dentro de cada nodo.

Esto no reemplaza los relojes de Lamport, pero reduce condiciones de carrera internas al implementar Ricart-Agrawala.

## Configuración recomendada

Cada nodo debe permitir configurar los tamaños de sus pools.

Ejemplo:

~~~properties
pool.clients=64
pool.peers=16
pool.scheduler=4
pool.coordination=1
~~~

## Justificación de tamaños iniciales

| Parámetro | Valor sugerido | Justificación |
|---|---:|---|
| `pool.clients` | `64` | Permite soportar la prueba de 50 clientes concurrentes con margen |
| `pool.peers` | `16` | Suficiente para procesar mensajes inter-nodo en una topología de 3 nodos |
| `pool.scheduler` | `4` | Permite separar heartbeats, detección de fallos y métricas |
| `pool.coordination` | `1` | Simplifica el procesamiento ordenado de coordinación distribuida |

Estos valores pueden ajustarse durante pruebas de carga.

## Timeouts mínimos recomendados

Además de los pools, se deben definir timeouts para evitar que conexiones inactivas ocupen workers indefinidamente.

Ejemplo:

~~~properties
socket.clientTimeoutMs=30000
socket.peerTimeoutMs=5000

heartbeat.intervalMs=2000
heartbeat.timeoutMs=6000
~~~

## Justificación de timeouts

| Timeout | Propósito |
|---|---|
| `socket.clientTimeoutMs` | Evitar que un cliente inactivo ocupe un worker indefinidamente |
| `socket.peerTimeoutMs` | Detectar problemas en comunicación inter-nodo |
| `heartbeat.intervalMs` | Definir cada cuánto se envían señales de vida |
| `heartbeat.timeoutMs` | Definir cuándo se sospecha que un nodo dejó de responder |

## Alternativas consideradas

### Alternativa 1: mantener Thread-per-Connection

Consiste en crear un hilo nuevo por cada cliente aceptado.

Se descarta porque:

- no limita la cantidad de hilos;
- escala mal ante muchas conexiones;
- aumenta riesgo de agotamiento de memoria;
- dificulta la prueba de carga;
- no separa tareas críticas del tráfico normal.

### Alternativa 2: usar un único thread-pool global

Consiste en tener un solo `ExecutorService` para clientes, peers, heartbeats y coordinación.

Se descarta porque:

- una ráfaga de clientes podría ocupar todos los workers;
- los heartbeats podrían retrasarse;
- podrían aparecer falsos positivos de caída;
- los mensajes de coordinación podrían no procesarse a tiempo;
- el sistema podría bloquear operaciones distribuidas por saturación local.

### Alternativa 3: usar pools separados por responsabilidad

Consiste en definir pools independientes para clientes, peers, tareas periódicas y coordinación.

Se selecciona porque:

- controla el consumo de recursos;
- mejora aislamiento entre responsabilidades;
- permite priorizar tareas críticas;
- facilita medir saturación;
- mejora la defensa arquitectónica;
- prepara al sistema para la prueba de carga.

## Consecuencias positivas

- Se evita la creación indefinida de hilos.
- Se mejora el control de concurrencia por nodo.
- Se reduce el riesgo de denegación de servicio por exceso de conexiones.
- Se separan tareas críticas de tareas normales.
- Los heartbeats no dependen del tráfico de clientes.
- La coordinación distribuida no depende del pool de clientes.
- Se facilita la recolección de métricas.
- Se puede ajustar el rendimiento por configuración.
- La arquitectura queda más preparada para 50 clientes concurrentes.

## Consecuencias negativas o costos

- Aumenta la complejidad de configuración.
- Se deben elegir tamaños adecuados para los pools.
- Un pool mal dimensionado puede generar colas o rechazos.
- Se deben manejar correctamente tareas bloqueantes.
- Se requiere cerrar los pools de forma ordenada al apagar el nodo.
- Se deben implementar timeouts para evitar workers ocupados indefinidamente.
- Es necesario registrar métricas para detectar saturación.

## Riesgos

### Riesgo 1: saturación del clientWorkerPool

Si demasiados clientes se conectan o quedan inactivos, el pool puede llenarse.

Mitigación:

- definir `pool.clients=64` para la demo;
- usar `socket.clientTimeoutMs`;
- registrar tareas activas y rechazadas;
- limpiar sesiones al desconectar.

### Riesgo 2: falso positivo de caída de nodo

Si los heartbeats se ejecutaran en el pool de clientes, una saturación de clientes podría retrasarlos y hacer creer que un nodo cayó.

Mitigación:

- ejecutar heartbeats en `schedulerPool`;
- no depender de `clientWorkerPool` para detección de fallos.

### Riesgo 3: bloqueo de coordinación distribuida

Si `MUTEX_REQUEST` o `MUTEX_REPLY` se procesan en el pool de clientes, una ráfaga de clientes podría bloquear Ricart-Agrawala.

Mitigación:

- recibir mensajes por `peerWorkerPool`;
- derivar lógica de coordinación al `coordinationExecutor`.

### Riesgo 4: conexiones bloqueantes

Los sockets TCP pueden bloquear mientras esperan lectura.

Mitigación:

- usar timeouts de socket;
- cerrar conexiones inactivas;
- manejar excepciones de red;
- liberar sesiones en bloques `finally`.

## Impacto sobre clases

La decisión implica crear o adaptar las siguientes clases:

| Clase | Impacto |
|---|---|
| `ServerNode` | Debe inicializar y administrar los pools |
| `ClientAcceptor` | Debe usar `clientWorkerPool.submit(...)` |
| `ClientConnectionHandler` | Debe ejecutarse dentro del pool de clientes |
| `PeerListener` | Debe usar `peerWorkerPool.submit(...)` |
| `PeerMessageHandler` | Debe ejecutarse dentro del pool de peers |
| `HeartbeatManager` | Debe usar `schedulerPool` |
| `FailureDetector` | Debe ejecutarse periódicamente |
| `MutualExclusionManager` | Debe usar `coordinationExecutor` |
| `MetricsCollector` | Debe registrar carga, latencia y errores |

## Reglas de implementación para Persona 2

Persona 2 debe implementar `ServerNode` respetando estas reglas:

1. No usar `new Thread(...)` por cada cliente.
2. Usar `clientWorkerPool.submit(...)` para clientes.
3. Usar `peerWorkerPool.submit(...)` para mensajes inter-nodo.
4. Crear `schedulerPool` para heartbeats y timeouts.
5. Crear `coordinationExecutor` para coordinación distribuida.
6. Leer tamaños de pool desde configuración.
7. Registrar logs de inicio de pools.
8. Cerrar pools ordenadamente al apagar el nodo.

Ejemplo conceptual:

~~~java
public class ServerNode {
    private final ExecutorService clientWorkerPool;
    private final ExecutorService peerWorkerPool;
    private final ScheduledExecutorService schedulerPool;
    private final ExecutorService coordinationExecutor;

    public ServerNode(NodeConfig config) {
        this.clientWorkerPool = Executors.newFixedThreadPool(config.getClientPoolSize());
        this.peerWorkerPool = Executors.newFixedThreadPool(config.getPeerPoolSize());
        this.schedulerPool = Executors.newScheduledThreadPool(config.getSchedulerPoolSize());
        this.coordinationExecutor = Executors.newSingleThreadExecutor();
    }
}
~~~

## Reglas de implementación para Persona 3

Persona 3 debe considerar que las solicitudes de clientes se ejecutarán dentro del `clientWorkerPool`.

Por lo tanto:

- los handlers deben limpiar recursos al terminar;
- las sesiones deben removerse al desconectar;
- las operaciones remotas deben delegarse al `MessageRouter`;
- no se deben crear hilos nuevos desde la lógica de negocio.

## Reglas de implementación para Persona 4

Persona 4 debe considerar que la coordinación distribuida no debe depender del pool de clientes.

Por lo tanto:

- `MUTEX_REQUEST` se recibe desde `peerWorkerPool`;
- `MUTEX_REPLY` se recibe desde `peerWorkerPool`;
- la lógica de decisión se deriva al `coordinationExecutor`;
- los eventos deben registrarse con Lamport.

## Reglas de implementación para Persona 5

Persona 5 debe considerar que los heartbeats y timeouts deben ejecutarse en `schedulerPool`.

Por lo tanto:

- no debe usar `clientWorkerPool` para heartbeats;
- no debe usar `clientWorkerPool` para detección de fallos;
- debe evitar falsos positivos por saturación de clientes;
- debe registrar eventos `ALIVE`, `SUSPECTED`, `DOWN` y `RECOVERING`.

## Métricas asociadas

La prueba de carga deberá registrar, cuando sea posible:

| Métrica | Propósito |
|---|---|
| `activeClientWorkers` | Ver cuántos workers de clientes están ocupados |
| `queuedClientTasks` | Ver acumulación de tareas |
| `rejectedClientTasks` | Detectar saturación |
| `activePeerWorkers` | Ver carga inter-nodo |
| `heartbeatTimeouts` | Medir sospechas o caídas |
| `coordinationMessages` | Medir costo de Ricart-Agrawala |
| `avgLatencyMs` | Latencia promedio |
| `p95LatencyMs` | Latencia p95 |
| `throughput` | Solicitudes por segundo |
| `errorRate` | Porcentaje de errores o pérdidas |

## Criterios de aceptación

Esta decisión se considera correctamente aplicada cuando:

1. `ServerNode` inicializa pools separados para clientes, peers, scheduler y coordinación.
2. Las conexiones de clientes no crean hilos manualmente sin control.
3. Los mensajes inter-nodo no usan el pool de clientes.
4. Los heartbeats se ejecutan mediante `schedulerPool`.
5. La coordinación distribuida usa un executor separado.
6. Los tamaños de pool son configurables.
7. Existen timeouts para sockets de clientes y peers.
8. Una sobrecarga de clientes no bloquea heartbeats.
9. Una sobrecarga de clientes no bloquea `MUTEX_REQUEST` ni `MUTEX_REPLY`.
10. La prueba de carga puede ejecutarse con 50 clientes concurrentes durante al menos 60 segundos.

## Decisión final

Se reemplaza el modelo Thread-per-Connection puro de la Entrega Inicial por un modelo de thread-pools acotados y separados por responsabilidad en cada `ServerNode`.

Esta decisión mejora el control de concurrencia, reduce el riesgo de agotamiento de recursos y permite que tareas críticas de la arquitectura distribuida, como heartbeats y coordinación, no dependan del tráfico normal de clientes.