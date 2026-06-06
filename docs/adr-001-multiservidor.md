# ADR-001: Evolución a arquitectura multiservidor

## Contexto

La Entrega Inicial del proyecto implementaba un sistema distribuido de mensajería instantánea inspirado en WhatsApp mediante una arquitectura Cliente-Servidor Broker centralizada.

En esa versión, múltiples clientes podían conectarse simultáneamente a un único servidor principal. Dicho servidor concentraba las responsabilidades centrales del sistema:

- aceptación de conexiones de clientes;
- registro de sesiones activas;
- enrutamiento de mensajes privados;
- gestión de grupos;
- broadcast de mensajes grupales;
- manejo de desconexiones;
- sincronización del estado compartido.

Esta arquitectura fue adecuada para demostrar conceptos iniciales del curso, tales como comunicación remota mediante sockets TCP, serialización de objetos, concurrencia entre clientes, transparencia de acceso y transparencia de ubicación.

Sin embargo, para la Entrega Final, esta arquitectura resulta insuficiente, porque mantiene un único punto de coordinación y de fallo. Si el servidor central cae, se satura o deja de responder, el sistema completo queda afectado.

La Entrega Final requiere que el sistema evolucione hacia una arquitectura donde existan varios procesos servidores independientes colaborando entre sí. El sistema debe evidenciar comunicación entre nodos, ausencia de reloj global, coordinación distribuida, tolerancia a fallos parciales y continuidad de servicio aunque uno de los nodos falle.

## Problema

El problema arquitectónico principal es que la Entrega Inicial depende de un broker centralizado. Esto genera las siguientes limitaciones:

1. Punto único de fallo:
   si el servidor principal cae, todos los clientes pierden el servicio.

2. Punto único de coordinación:
   todas las sesiones, grupos y mensajes dependen de una única entidad central.

3. Escalabilidad limitada:
   el crecimiento de clientes y mensajes queda condicionado por la capacidad de un solo servidor.

4. Baja evidencia de distribución real entre servidores:
   aunque existen múltiples procesos cliente, no existen múltiples nodos servidores cooperando.

5. Imposibilidad de demostrar recuperación distribuida:
   al existir un único servidor, no se puede mostrar que el resto del sistema continúe funcionando cuando un nodo servidor cae.

6. Ausencia de coordinación distribuida real:
   los mecanismos de sincronización de la entrega inicial protegen recursos locales del servidor, pero no coordinan recursos compartidos entre varios nodos.

Por lo tanto, la Entrega Final debe reemplazar el broker único por una arquitectura donde varios servidores independientes colaboren mediante paso de mensajes.

## Decisión

Se adopta una arquitectura multiservidor.

El sistema final estará compuesto por al menos tres nodos servidores independientes:

- `node1`
- `node2`
- `node3`

Cada nodo será una instancia de `ServerNode`, ejecutada como un proceso/JVM independiente. Cada `ServerNode` cumplirá dos roles:

1. Servidor de clientes locales:
   acepta conexiones de clientes mediante sockets TCP en un puerto propio.

2. Peer distribuido:
   se comunica con otros `ServerNode` mediante sockets TCP en un puerto inter-nodo.

La arquitectura mantiene el modelo cliente-servidor en el borde del sistema, porque los clientes siguen conectándose a un nodo servidor. Sin embargo, el backend deja de ser centralizado y pasa a estar compuesto por varios nodos servidores que colaboran entre sí.

Por lo tanto, la arquitectura final se define como:

~~~text
Arquitectura híbrida multiservidor:

Cliente -> ServerNode local
ServerNode <-> ServerNode
~~~

## Nodos mínimos definidos

| Nodo lógico | Host de demo | Puerto clientes | Puerto inter-nodo |
|---|---|---:|---:|
| `node1` | `localhost` | `5001` | `6001` |
| `node2` | `localhost` | `5002` | `6002` |
| `node3` | `localhost` | `5003` | `6003` |

Cada nodo debe poder iniciarse con una configuración propia. Ejemplo:

~~~bash
java whatsapp.server.ServerNode node1 5001 6001 config/node1.properties
java whatsapp.server.ServerNode node2 5002 6002 config/node2.properties
java whatsapp.server.ServerNode node3 5003 6003 config/node3.properties
~~~

## Configuración esperada por nodo

Cada nodo deberá contar con un archivo `.properties` que defina su identidad, puertos y peers conocidos.

Ejemplo para `node1`:

~~~properties
node.id=node1
node.host=localhost
node.clientPort=5001
node.peerPort=6001

node.peers=node2@localhost:6002,node3@localhost:6003
~~~

## Funciones principales mantenidas

Se mantienen las dos funciones principales de la Entrega Inicial, pero ahora adaptadas a una arquitectura distribuida entre servidores.

### Función principal 1: mensajería privada distribuida

Un cliente conectado a `node1` puede enviar un mensaje privado a un usuario conectado a `node2` o `node3`.

El cliente emisor no necesita conocer la ubicación física del destinatario. Solo conoce su identificador lógico de usuario.

Flujo general:

1. Cliente A envía mensaje privado a Cliente B.
2. `node1` recibe el mensaje.
3. `node1` revisa si Cliente B está conectado localmente.
4. Si Cliente B no está local, `node1` consulta el directorio distribuido de usuarios.
5. `node1` detecta que Cliente B está en `node2`.
6. `node1` reenvía el mensaje a `node2`.
7. `node2` entrega el mensaje a Cliente B.

### Función principal 2: mensajería grupal distribuida

Un cliente conectado a cualquier nodo puede enviar un mensaje a un grupo cuyos miembros estén distribuidos entre varios `ServerNode`.

Flujo general:

1. Cliente A envía un mensaje al grupo `grupoX`.
2. El nodo local valida que el grupo exista.
3. El nodo local valida que Cliente A pertenezca al grupo.
4. El nodo local identifica miembros locales y remotos.
5. Entrega el mensaje a miembros locales.
6. Reenvía el mensaje a los nodos que tengan miembros remotos.
7. Cada nodo remoto entrega el mensaje a sus clientes locales correspondientes.

## Estado mantenido por cada ServerNode

| Estado | Tipo | Ubicación | Consistencia esperada |
|---|---|---|---|
| Sesiones locales | Local | Solo en el nodo donde está conectado el cliente | Fuerte local |
| Directorio usuario-nodo | Distribuido/replicado | Todos los nodos | Eventual |
| Grupos y membresías | Distribuido/replicado | Todos los nodos | Coordinada |
| Membresía de nodos | Distribuido | Todos los nodos | Eventual |
| Reloj de Lamport | Local por nodo | Cada `ServerNode` | Actualizado por evento |
| Métricas | Local | Cada `ServerNode` | Consolidable al final |

## Regla de distribución de estado

El sistema no debe distribuir objetos asociados a conexiones locales, tales como:

- sockets;
- `ObjectInputStream`;
- `ObjectOutputStream`;
- `ClientHandler`;
- `ManejadorCliente`.

Estos objetos pertenecen al proceso local y no pueden trasladarse a otro nodo.

Lo que se distribuye entre nodos es información lógica, por ejemplo:

~~~text
userId -> nodeId
groupId -> miembros
nodeId -> estado del nodo
~~~

## Componentes arquitectónicos principales

Cada `ServerNode` deberá contener, al menos, los siguientes componentes:

| Componente | Responsabilidad |
|---|---|
| `ClientAcceptor` | Aceptar conexiones de clientes locales |
| `ClientConnectionHandler` | Procesar mensajes de clientes conectados al nodo |
| `PeerListener` | Escuchar conexiones o mensajes de otros nodos |
| `PeerConnectionManager` | Enviar mensajes hacia otros `ServerNode` |
| `PeerMessageHandler` | Procesar mensajes recibidos desde otros nodos |
| `MembershipManager` | Mantener la lista de nodos conocidos y su estado |
| `LocalSessionManager` | Mantener sesiones conectadas localmente |
| `GlobalUserDirectory` | Resolver en qué nodo se encuentra un usuario |
| `DistributedGroupManager` | Mantener grupos y membresías distribuidas |
| `MessageRouter` | Decidir si un mensaje se entrega localmente o se reenvía |
| `LamportClock` | Registrar orden lógico de eventos distribuidos |
| `MutualExclusionManager` | Coordinar acceso a recurso crítico distribuido |
| `HeartbeatManager` | Enviar y recibir señales de vida entre nodos |
| `MetricsCollector` | Registrar métricas de carga, latencia y errores |

## Recurso crítico distribuido

El recurso crítico distribuido definido para la Entrega Final será:

~~~text
GROUP_REGISTRY
~~~

Este recurso representa el registro lógico de grupos y membresías.

Las operaciones protegidas serán:

- crear grupo;
- unirse a grupo;
- modificar membresía;
- salir de grupo, si se implementa;
- replicar cambios de membresía entre nodos.

## Justificación del recurso crítico

La gestión de grupos es un recurso compartido distribuido. Si dos nodos modifican simultáneamente la existencia de un grupo o su membresía, podrían aparecer inconsistencias, por ejemplo:

- dos nodos crean el mismo grupo con distinta composición;
- un usuario aparece como miembro en un nodo, pero no en otro;
- un mensaje grupal se entrega a una membresía desactualizada;
- un nodo acepta una operación que otro nodo aún no conoce.

Por esto, las operaciones sobre `GROUP_REGISTRY` deberán coordinarse mediante exclusión mutua distribuida.

## Algoritmos asociados

La arquitectura final deja definidos los siguientes mecanismos para las siguientes personas del equipo:

| Mecanismo | Responsable posterior | Propósito |
|---|---|---|
| Lista de membresía | Persona 2 | Permitir que los nodos se conozcan |
| Enrutamiento entre nodos | Persona 3 | Enviar mensajes a usuarios remotos |
| Relojes de Lamport | Persona 4 | Registrar orden lógico sin reloj global |
| Ricart-Agrawala | Persona 4 | Proteger `GROUP_REGISTRY` |
| Heartbeats/timeouts | Persona 5 | Detectar fallos crash u omisión |
| Generador de carga | Persona 6 | Medir rendimiento y recuperación |

## Supuestos del sistema

### Red

Se asume comunicación mediante sockets TCP.

TCP entrega bytes de forma ordenada dentro de una conexión, pero una conexión puede fallar, cerrarse, bloquearse o no responder dentro del timeout configurado.

### Nodos

Se consideran fallos de tipo:

- crash;
- omisión;
- desconexión de cliente;
- pérdida práctica de comunicación por timeout.

No se considera tolerancia a fallos bizantinos.

### Sincronía

El sistema se modela de forma práctica como parcialmente sincrónico para efectos de detección de fallos.

Un timeout no demuestra matemáticamente que un nodo cayó, pero permite sospechar la falla y reconfigurar rutas disponibles.

### Tiempo

No se utilizará `System.currentTimeMillis()` para ordenar eventos distribuidos.

El tiempo físico se usará solo para métricas de latencia y rendimiento.

El orden distribuido se registrará mediante relojes de Lamport.

## Alternativas consideradas

### Alternativa 1: mantener broker centralizado

Consistía en mantener el diseño de la Entrega Inicial, donde todos los clientes se conectan a un único servidor principal.

Se descarta porque:

- conserva un punto único de fallo;
- no demuestra colaboración entre nodos servidores;
- no permite evidenciar recuperación parcial ante caída de un servidor;
- no cumple adecuadamente la exigencia de arquitectura multinodo.

### Alternativa 2: arquitectura P2P pura

Consistía en que cada cliente o nodo actuara como par completo dentro del sistema, sin servidores diferenciados.

Se descarta porque:

- obliga a rediseñar casi todo el proyecto;
- aumenta la complejidad de descubrimiento, seguridad y consistencia;
- dificulta reutilizar el código existente;
- puede exceder el alcance realista del equipo.

### Alternativa 3: arquitectura multiservidor

Consiste en mantener clientes relativamente simples y distribuir la capa servidora en varios `ServerNode`.

Se selecciona porque:

- permite reutilizar parte importante del parcial;
- elimina el broker único como centro exclusivo del sistema;
- permite demostrar comunicación servidor-servidor;
- permite agregar relojes lógicos y coordinación distribuida;
- permite inducir fallas en un nodo sin detener todo el sistema;
- es más realista para el alcance del proyecto.

## Consecuencias positivas

- El sistema deja de depender de un único servidor central.
- Se puede demostrar comunicación real entre procesos servidores.
- Se mantiene transparencia de ubicación para los clientes.
- Se habilita enrutamiento entre nodos.
- Se puede demostrar tolerancia parcial a fallos.
- Se puede instrumentar una prueba de carga distribuida.
- Se facilita justificar conceptos del curso: ausencia de reloj global, fallos independientes, coordinación y comunicación por mensajes.

## Consecuencias negativas o costos

- Aumenta la complejidad del diseño.
- Se deben implementar mensajes entre nodos.
- Se debe mantener una lista de membresía.
- Se debe separar estado local y distribuido.
- Se deben manejar fallos de nodos remotos.
- Se deben registrar logs más completos.
- Se debe evitar inconsistencia en grupos y membresías.
- Se requiere mayor coordinación entre integrantes del equipo.

## Impacto sobre otras personas del equipo

### Persona 2

Debe implementar la base multiservidor:

- `ServerNode`;
- `NodeInfo`;
- `NodeConfig`;
- `MembershipManager`;
- `PeerListener`;
- `PeerConnectionManager`;
- `NodeMessage`;
- comunicación TCP entre nodos.

### Persona 3

Debe adaptar las funciones principales:

- login distribuido;
- sesión local;
- directorio usuario-nodo;
- mensaje privado remoto;
- mensaje grupal remoto;
- grupos distribuidos.

### Persona 4

Debe implementar:

- `LamportClock`;
- timestamps lógicos en mensajes;
- Ricart-Agrawala;
- protección de `GROUP_REGISTRY`.

### Persona 5

Debe implementar:

- heartbeats;
- timeouts;
- detección de nodos caídos;
- estados `ALIVE`, `SUSPECTED`, `DOWN`, `RECOVERING`;
- recuperación parcial.

### Persona 6

Debe validar:

- prueba de carga;
- métricas;
- logs;
- falla inducida;
- documentación final.

## Criterios de aceptación

Esta decisión se considera correctamente aplicada cuando:

1. Existen al menos tres procesos `ServerNode` ejecutándose de forma independiente.
2. Cada nodo tiene un puerto para clientes y un puerto inter-nodo.
3. Los nodos se conocen mediante configuración o membresía.
4. Un cliente conectado a `node1` puede enviar un mensaje privado a un cliente conectado a `node2`.
5. Un mensaje grupal puede entregarse a miembros conectados en distintos nodos.
6. La caída de un nodo no detiene completamente el sistema.
7. Los eventos relevantes quedan registrados con identificación de nodo.
8. Las operaciones críticas sobre grupos quedan preparadas para coordinación distribuida.
9. El diseño permite incorporar relojes de Lamport, heartbeats y métricas.

## Decisión final

Se adopta una arquitectura multiservidor compuesta por tres `ServerNode` mínimos, con comunicación cliente-servidor en el borde y comunicación servidor-servidor en la capa distribuida.

Esta decisión reemplaza el broker centralizado de la Entrega Inicial y constituye la base arquitectónica de la Entrega Final.