# Matriz de evolución: Entrega Inicial a Entrega Final

## Propósito del documento

Este documento resume la evolución arquitectónica y técnica del sistema de mensajería instantánea inspirado en WhatsApp desde la Entrega Inicial hacia la Entrega Final.

La Entrega Inicial permitió validar una aplicación distribuida básica mediante sockets TCP, serialización de objetos, concurrencia de clientes y gestión centralizada de sesiones y grupos. Sin embargo, dicha versión estaba basada en un broker centralizado, por lo que no era suficiente para demostrar una arquitectura multinodo con coordinación distribuida, ausencia de reloj global y tolerancia a fallos independientes entre nodos servidores.

La Entrega Final transforma esa base en una arquitectura multiservidor, compuesta por varios `ServerNode` independientes que aceptan clientes locales y se comunican entre sí mediante mensajes inter-nodo.

---

## Resumen ejecutivo de la evolución

| Dimensión | Entrega Inicial | Entrega Final |
|---|---|---|
| Arquitectura general | Cliente-Servidor Broker centralizado | Arquitectura multiservidor |
| Nodo servidor | Único `ServidorPrincipal` | Tres o más `ServerNode` |
| Comunicación | Cliente-servidor | Cliente-servidor + servidor-servidor |
| Concurrencia | Thread-per-Connection | Thread-pools acotados por nodo |
| Sesiones | `SessionManager` centralizado | `LocalSessionManager` + `GlobalUserDirectory` |
| Grupos | `GroupManager` centralizado | `DistributedGroupManager` coordinado |
| Enrutamiento | Centralizado en el servidor | Distribuido mediante `MessageRouter` |
| Tiempo | Logs locales y tiempo físico para observación | Relojes de Lamport para orden lógico |
| Coordinación | Locks locales Java | Ricart-Agrawala para recurso crítico distribuido |
| Fallos | Desconexión o crash de clientes | Crash/omisión de clientes y nodos servidores |
| Detección de fallos | Excepciones de socket | Heartbeats y timeouts |
| Prueba de carga | Pruebas funcionales básicas | 50 clientes/hilos durante al menos 60 segundos |
| Métricas | Logs funcionales | Throughput, latencia promedio, p95, errores y mensajes de coordinación |

---

## Matriz detallada de evolución

| Aspecto | Entrega Inicial | Limitación detectada | Entrega Final | Justificación |
|---|---|---|---|---|
| Arquitectura | Cliente-Servidor Broker centralizada | Todo depende de un único servidor | Arquitectura multiservidor | Permite distribuir responsabilidades entre varios nodos |
| Servidor principal | `ServidorPrincipal` único | Punto único de fallo y coordinación | `ServerNode` por nodo | Cada nodo puede aceptar clientes y comunicarse con otros nodos |
| Cantidad de nodos servidores | 1 servidor | No evidencia colaboración entre servidores | Mínimo 3 nodos: `node1`, `node2`, `node3` | Permite demostrar comunicación, coordinación y fallos independientes |
| Conexión de clientes | Todos los clientes se conectan al mismo servidor | No hay distribución real de usuarios | Clientes distribuidos entre distintos `ServerNode` | Permite enviar mensajes entre usuarios conectados a nodos distintos |
| Comunicación entre servidores | No existe | El sistema no tiene capa distribuida entre nodos | Comunicación TCP inter-nodo | Permite enrutamiento remoto, membresía, heartbeats y coordinación |
| Puertos | Puerto único para servidor | No permite ejecutar varios nodos en la misma máquina | Puerto de clientes y puerto inter-nodo por cada nodo | Se separa tráfico cliente-servidor y servidor-servidor |
| Identidad de nodos | No aplica | No hay nodos distinguibles | IDs lógicos: `node1`, `node2`, `node3` | Facilita membresía, logs, enrutamiento y ordenamiento |
| Modelo de concurrencia | Thread-per-Connection | Puede crecer indefinidamente con muchos clientes | Thread-pools acotados por responsabilidad | Controla consumo de recursos y soporta mejor la prueba de carga |
| Gestión de hilos | Un hilo dedicado por cliente | Riesgo de saturación por conexiones inactivas | `clientWorkerPool`, `peerWorkerPool`, `schedulerPool`, `coordinationExecutor` | Se separan tareas normales y tareas críticas |
| Atención de clientes | `ManejadorCliente` por conexión | Depende de un servidor único | `ClientConnectionHandler` ejecutado por `clientWorkerPool` | Mantiene concurrencia, pero con límite configurable |
| Comunicación inter-nodo | No existe | No se pueden reenviar mensajes remotos | `PeerListener`, `PeerConnectionManager`, `PeerMessageHandler` | Permite que los nodos cooperen mediante mensajes |
| Sesiones activas | `SessionManager` centralizado | Solo sabe usuarios del servidor único | `LocalSessionManager` por nodo | Cada nodo administra solo sus clientes locales |
| Ubicación de usuarios | Implícita en el servidor central | No sirve para usuarios distribuidos | `GlobalUserDirectory` con `userId -> nodeId` | Permite localizar usuarios conectados a otros nodos |
| Grupos | `GroupManager` centralizado | No coordina membresías entre nodos | `DistributedGroupManager` | Permite grupos con miembros repartidos entre nodos |
| Estado de grupo | Local al servidor central | No hay replicación ni coordinación distribuida | Registro lógico distribuido `GROUP_REGISTRY` | Permite coordinar creación y modificación de grupos |
| Recurso crítico | Recursos locales del servidor | La sincronización local no resuelve conflictos entre nodos | `GROUP_REGISTRY` | Protege operaciones de grupo con exclusión mutua distribuida |
| Sincronización local | `synchronized`, Monitor, `ReentrantReadWriteLock` | Solo protege memoria local | Locks locales + coordinación distribuida | Se mantiene sincronización local y se agrega coordinación entre nodos |
| Coordinación distribuida | No existe | No se evidencia algoritmo distribuido | Ricart-Agrawala | Permite controlar acceso a sección crítica distribuida |
| Reloj del sistema | Tiempo físico o logs locales | No permite ordenar eventos distribuidos | Relojes de Lamport | Ordena eventos por causalidad lógica, no por hora física |
| Marcas de eventos | Logs sin reloj lógico | No permiten defender ausencia de reloj global | Logs con `[nodeId][L=x]` | Evidencia eventos distribuidos sin reloj global |
| Mensaje privado | Cliente A envía a Cliente B mediante servidor central | Solo funciona dentro del servidor único | Mensaje privado distribuido | Permite enviar desde un cliente en `node1` a otro en `node2` |
| Mensaje grupal | Broadcast controlado por servidor central | No distribuye el grupo entre nodos | Broadcast distribuido por nodos | Cada nodo entrega a sus miembros locales y reenvía a nodos remotos |
| Transparencia de ubicación | Cliente usa identificadores lógicos | Parcial, porque todo vive en un servidor | Cliente sigue usando identificadores lógicos | El cliente no necesita conocer en qué nodo está el destinatario |
| Transparencia de acceso | Uso de paquetes serializables | Válida solo cliente-servidor | Paquetes cliente-servidor + paquetes servidor-servidor | Se mantiene la abstracción de comunicación remota |
| Marshalling | Serialización nativa Java | Solo para comunicación cliente-servidor | Serialización Java para mensajes entre clientes y nodos | Se reutiliza el mecanismo y se amplía a `NodeMessage` |
| Fallos de cliente | Se maneja desconexión abrupta | Solo se tolera caída de clientes | Se mantiene y amplía | La caída de un cliente no debe afectar a otros clientes ni nodos |
| Fallos de servidor | No tolerado | Si cae el servidor, cae todo el sistema | Tolerancia parcial ante caída de un `ServerNode` | Los nodos restantes deben seguir operando |
| Detección de fallos | Excepciones al enviar o recibir | Reactivo y local | Heartbeats y timeouts | Permite sospechar nodos caídos o incomunicados |
| Estados de nodo | No aplica | No hay membresía | `ALIVE`, `SUSPECTED`, `DOWN`, `RECOVERING` | Permite razonar sobre fallos y recuperación |
| Recuperación | Limpieza de sesión local | No hay reintegración de servidor | Reintegración básica de nodo | Un nodo puede volver y sincronizar estado mínimo |
| Métricas | No formalizadas | Difícil comparar rendimiento | Métricas obligatorias de carga | Permite evaluar throughput, latencia y errores |
| Prueba de carga | Pruebas manuales o funcionales | No demuestra comportamiento bajo estrés | `LoadGenerator` con 50 clientes/hilos por 60 segundos | Cumple exigencia de evaluación final |
| Latencia | No medida formalmente | No se puede evaluar impacto de distribución | Latencia promedio y p95 | Permite analizar rendimiento normal y con falla |
| Throughput | No medido formalmente | No se puede cuantificar capacidad | Solicitudes/mensajes por segundo | Permite evaluar capacidad del sistema |
| Mensajes de coordinación | No aplica | No hay algoritmo de coordinación | Conteo de `MUTEX_REQUEST` y `MUTEX_REPLY` | Permite medir costo de coordinación |
| Tasa de error | No medida formalmente | No se cuantifica pérdida o fallo | Porcentaje de errores/pérdidas | Permite comparar régimen normal y con falla inducida |
| Seguridad | Limitaciones identificadas en el parcial | Sin autenticación real ni cifrado | Se mantienen limitaciones y se documentan | La entrega final se enfoca en distribución, no en seguridad productiva |
| Informe técnico | Describe arquitectura centralizada | Debe actualizarse completamente | Informe con arquitectura multinodo | Debe reflejar coherencia entre código, modelos y métricas |
| Diagramas | Modelo físico y arquitectónico centralizado | Ya no representan la entrega final | Modelo físico y arquitectónico multiservidor | Deben mostrar tres nodos y comunicación inter-nodo |
| UML de secuencia | Login, mensaje privado y grupo centralizados | No muestran paso entre servidores | Secuencias distribuidas | Deben mostrar mensajes entre `ServerNode` |
| README | Ejecución de un servidor y clientes | Insuficiente para final | Ejecución de tres nodos, clientes y carga | Debe permitir reproducir la demo |

---

## Evolución del modelo físico

### Entrega Inicial

En la Entrega Inicial, el modelo físico se componía de:

- múltiples clientes;
- un único servidor central;
- una conexión TCP entre cada cliente y el servidor.

Representación conceptual:

~~~text
Cliente A ─┐
Cliente B ─┼──> ServidorPrincipal
Cliente C ─┘
~~~

### Entrega Final

En la Entrega Final, el modelo físico se compone de:

- múltiples clientes;
- tres nodos servidores mínimos;
- conexiones cliente-servidor;
- conexiones servidor-servidor.

Representación conceptual:

~~~text
Cliente A ──> node1
Cliente B ──> node2
Cliente C ──> node3

node1 <──> node2
node1 <──> node3
node2 <──> node3
~~~

---

## Evolución del modelo arquitectónico

### Entrega Inicial

La arquitectura inicial concentraba el estado y las decisiones en el servidor central:

~~~text
ServidorPrincipal
 ├── ManejadorCliente
 ├── SessionManager
 ├── GroupManager
 └── PaqueteRed
~~~

### Entrega Final

La arquitectura final divide responsabilidades por nodo:

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

## Evolución de las funciones principales

### Función 1: mensajería privada

#### Entrega Inicial

El flujo era centralizado:

~~~text
Cliente A -> ServidorPrincipal -> Cliente B
~~~

El servidor central consultaba su `SessionManager` y entregaba el mensaje si el destinatario estaba conectado.

#### Entrega Final

El flujo pasa a ser distribuido:

~~~text
Cliente A -> node1 -> node2 -> Cliente B
~~~

El nodo local debe:

1. recibir el mensaje;
2. verificar si el destinatario está local;
3. consultar `GlobalUserDirectory`;
4. reenviar el mensaje al nodo remoto;
5. registrar el evento con Lamport;
6. recibir confirmación o error.

---

### Función 2: mensajería grupal

#### Entrega Inicial

El flujo era centralizado:

~~~text
Cliente A -> ServidorPrincipal -> miembros del grupo
~~~

El servidor central revisaba el `GroupManager` y enviaba el mensaje a todos los miembros conectados.

#### Entrega Final

El flujo pasa a ser distribuido:

~~~text
Cliente A -> node1
node1 -> miembros locales
node1 -> node2 -> miembros locales en node2
node1 -> node3 -> miembros locales en node3
~~~

El nodo local debe:

1. validar grupo;
2. validar membresía del emisor;
3. identificar miembros locales y remotos;
4. entregar localmente;
5. reenviar a nodos remotos;
6. registrar eventos con Lamport;
7. manejar nodos caídos o no disponibles.

---

## Evolución de concurrencia

### Entrega Inicial

La concurrencia se basaba en Thread-per-Connection:

~~~text
Cliente nuevo -> nuevo hilo
Cliente nuevo -> nuevo hilo
Cliente nuevo -> nuevo hilo
~~~

Ventajas:

- simple de implementar;
- fácil de entender;
- aísla parcialmente clientes.

Limitaciones:

- crece con la cantidad de clientes;
- puede agotar recursos;
- no separa tráfico de clientes, peers y coordinación;
- no es ideal para una prueba de carga.

### Entrega Final

La concurrencia se basa en thread-pools acotados:

~~~text
clientWorkerPool      -> clientes
peerWorkerPool        -> mensajes entre nodos
schedulerPool         -> heartbeats/timeouts/métricas
coordinationExecutor  -> coordinación distribuida
~~~

Ventajas:

- limita el número de hilos;
- separa responsabilidades;
- evita que clientes bloqueen heartbeats;
- evita que clientes bloqueen coordinación;
- facilita métricas de saturación.

---

## Evolución de fallos

### Entrega Inicial

Fallos considerados:

- cliente desconectado;
- error al enviar mensaje;
- fallo parcial en broadcast grupal;
- excepción de socket.

El servidor central seguía funcionando si un cliente fallaba.

### Entrega Final

Fallos considerados:

- cliente desconectado;
- nodo servidor caído;
- nodo servidor sospechoso;
- omisión de mensaje;
- timeout inter-nodo;
- reintegración básica de nodo;
- error durante comunicación remota.

Estados sugeridos para nodos:

~~~text
ALIVE
SUSPECTED
DOWN
RECOVERING
~~~

---

## Evolución del tiempo

### Entrega Inicial

El sistema podía registrar eventos en logs, pero no contaba con un mecanismo explícito de orden lógico distribuido.

El tiempo físico podía servir para depuración o medición local, pero no para afirmar orden global de eventos.

### Entrega Final

El sistema incorporará relojes de Lamport.

Cada evento relevante debe quedar registrado con:

~~~text
[nodeId][L=valor] evento
~~~

Ejemplos:

~~~text
[node1][L=15] PRIVATE_MESSAGE_SEND usuarioA -> usuarioB
[node2][L=18] PRIVATE_MESSAGE_RECEIVE usuarioA -> usuarioB
[node1][L=21] MUTEX_REQUEST GROUP_REGISTRY
[node1][L=25] ENTER_CRITICAL_SECTION GROUP_REGISTRY
~~~

---

## Evolución de coordinación

### Entrega Inicial

La coordinación estaba limitada a mecanismos locales de Java:

- `synchronized`;
- patrón Monitor;
- `ReentrantReadWriteLock`;
- sincronización de `ObjectOutputStream`.

Estos mecanismos protegen memoria local dentro del servidor.

### Entrega Final

Se mantiene la sincronización local, pero se agrega coordinación distribuida.

El recurso crítico será:

~~~text
GROUP_REGISTRY
~~~

Este recurso será protegido mediante:

~~~text
Lamport + Ricart-Agrawala
~~~

Operaciones protegidas:

- crear grupo;
- unirse a grupo;
- modificar membresía;
- salir de grupo, si se implementa.

---

## Evolución de pruebas

### Entrega Inicial

Pruebas principalmente funcionales:

- conexión de clientes;
- login;
- mensaje privado;
- creación de grupo;
- unión a grupo;
- mensaje grupal;
- desconexión de cliente.

### Entrega Final

Pruebas funcionales + prueba de carga:

- ejecución de tres nodos;
- conexión de clientes a distintos nodos;
- mensaje privado entre nodos;
- mensaje grupal entre nodos;
- actualización distribuida de grupos;
- caída inducida de un nodo;
- recuperación parcial;
- 50 clientes/hilos concurrentes;
- duración mínima de 60 segundos;
- métricas comparativas normal vs falla.

---

## Métricas esperadas en la Entrega Final

| Métrica | Régimen normal | Con falla inducida |
|---|---:|---:|
| Throughput | Por medir | Por medir |
| Latencia promedio | Por medir | Por medir |
| Latencia p95 | Por medir | Por medir |
| Mensajes de coordinación | Por medir | Por medir |
| Errores o pérdidas | Por medir | Por medir |
| Tiempo de recuperación | No aplica | Por medir |
| Heartbeat timeouts | Por medir | Por medir |
| Tareas rechazadas por pool | Por medir | Por medir |

---

## Componentes que se reutilizan

| Componente de Entrega Inicial | Reutilización en Entrega Final |
|---|---|
| Paquetes serializables | Se mantienen y se amplían |
| Cliente base | Se adapta para conectarse a distintos nodos |
| Lógica de mensaje privado | Se reutiliza parcialmente |
| Lógica de grupo | Se reutiliza parcialmente |
| `SessionManager` | Evoluciona a `LocalSessionManager` |
| `GroupManager` | Evoluciona a `DistributedGroupManager` |
| Sincronización de salida | Se mantiene para evitar corrupción de streams |
| Manejo de desconexión de cliente | Se mantiene y se adapta |

---

## Componentes que deben cambiar

| Componente actual | Cambio requerido |
|---|---|
| `ServidorPrincipal` | Reemplazar o evolucionar a `ServerNode` |
| `ManejadorCliente` | Evolucionar a `ClientConnectionHandler` |
| `SessionManager` central | Separar en local y directorio global |
| `GroupManager` central | Adaptar a estado distribuido/coordinado |
| Puerto único | Separar puerto de clientes y puerto inter-nodo |
| Thread-per-Connection | Reemplazar por thread-pools |
| Logs simples | Agregar nodeId y Lamport |
| Pruebas manuales | Agregar generador de carga y métricas |

---

## Componentes nuevos necesarios

| Componente nuevo | Propósito |
|---|---|
| `ServerNode` | Nodo servidor independiente |
| `NodeInfo` | Representar identidad, host, puertos y estado de nodo |
| `NodeConfig` | Cargar configuración del nodo |
| `MembershipManager` | Mantener lista de nodos conocidos |
| `PeerListener` | Escuchar mensajes inter-nodo |
| `PeerConnectionManager` | Enviar mensajes a otros nodos |
| `PeerMessageHandler` | Procesar mensajes recibidos desde nodos |
| `NodeMessage` | Clase base para mensajes servidor-servidor |
| `NodeMessageType` | Enumeración de mensajes inter-nodo |
| `GlobalUserDirectory` | Resolver ubicación de usuarios |
| `DistributedGroupManager` | Administrar grupos distribuidos |
| `MessageRouter` | Decidir entrega local/remota |
| `LamportClock` | Mantener reloj lógico |
| `MutualExclusionManager` | Implementar Ricart-Agrawala |
| `HeartbeatManager` | Enviar y recibir heartbeats |
| `FailureDetector` | Detectar nodos sospechosos o caídos |
| `MetricsCollector` | Registrar métricas del sistema |
| `LoadGenerator` | Ejecutar prueba de carga |

---

## Riesgos de la evolución

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Sobrediseñar la arquitectura | Retraso en implementación | Mantener solo tres nodos y funciones mínimas |
| Implementar Raft completo | Complejidad excesiva | Usar Lamport + Ricart-Agrawala |
| Mezclar todos los hilos en un pool | Bloqueo de heartbeats o coordinación | Separar pools por responsabilidad |
| Replicar mal los grupos | Inconsistencia de membresías | Proteger `GROUP_REGISTRY` |
| Usar reloj físico como orden global | Error conceptual | Usar Lamport para eventos distribuidos |
| No medir carga desde el inicio | Falta de evidencia final | Construir métricas junto con el sistema |
| Prometer tolerancia bizantina | Sobrealcance | Declararla fuera de alcance |
| Dejar un broker central oculto | Incumplimiento arquitectónico | Asegurar que los nodos cooperen directamente |

---

## Criterios de aceptación de la evolución

La evolución se considera lograda cuando:

1. Existen al menos tres `ServerNode` ejecutándose como procesos independientes.
2. Cada nodo acepta clientes en su propio puerto.
3. Cada nodo tiene comunicación TCP con los demás nodos.
4. Los clientes pueden conectarse a distintos nodos.
5. Un mensaje privado puede viajar de un cliente en `node1` a otro en `node2`.
6. Un mensaje grupal puede entregarse a miembros conectados en distintos nodos.
7. Los grupos y membresías no dependen de un broker central único.
8. La creación o modificación de grupos queda preparada para coordinación distribuida.
9. Los eventos distribuidos se registran con reloj de Lamport.
10. Los nodos detectan fallos mediante heartbeats y timeouts.
11. La caída de un nodo no detiene completamente el sistema.
12. El servidor usa thread-pools acotados, no hilos ilimitados por cliente.
13. Existe una prueba de carga con 50 clientes/hilos durante al menos 60 segundos.
14. Se generan métricas de throughput, latencia promedio, p95, errores y coordinación.
15. Los diagramas e informe reflejan la misma arquitectura que el código.

---

## Conclusión

La Entrega Final no debe entenderse como una reescritura completa del proyecto, sino como una evolución arquitectónica controlada.

La Entrega Inicial validó las funciones esenciales de mensajería, comunicación remota, concurrencia local y serialización. La Entrega Final toma esas bases y las transforma en un sistema realmente distribuido a nivel de servidores, incorporando nodos independientes, comunicación inter-nodo, thread-pools, relojes lógicos, coordinación distribuida, detección de fallos y métricas de carga.

El cambio central es pasar de:

~~~text
Un servidor central que controla todo
~~~

a:

~~~text
Varios ServerNode que cooperan mediante mensajes
~~~

Esta evolución permite al sistema alinearse con los objetivos de la Entrega Final y con los conceptos fundamentales del curso: concurrencia, ausencia de reloj global, comunicación por mensajes, coordinación distribuida y fallos independientes.