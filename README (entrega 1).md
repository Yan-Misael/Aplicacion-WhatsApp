# Sistema Distribuido de Mensajería Instantánea Inspirado en WhatsApp

Proyecto desarrollado para la asignatura **Computación Paralela y Distribuida ICI4344-1**.

Este proyecto implementa un prototipo distribuido de mensajería instantánea en Java, inspirado en el funcionamiento básico de aplicaciones como WhatsApp. El sistema permite la comunicación entre múltiples clientes mediante un servidor central, incorporando chat privado, chat grupal, concurrencia, serialización de objetos y manejo básico de fallos de clientes.

## Integrantes

- Benjamin Leiva
- Felipe Astudillo
- Francisca Guzmán
- Ian Guerrero
- Ignacio Reyes
- Benjamin Soto

## Descripción general

El sistema utiliza una arquitectura **Cliente-Servidor Broker centralizada**. Los clientes no se comunican directamente entre sí, sino que envían sus solicitudes al servidor. El servidor recibe los paquetes, identifica la operación solicitada, consulta el estado global del sistema y reenvía los mensajes al destinatario correspondiente.

La aplicación fue desarrollada en Java y utiliza:

- Sockets TCP/IP.
- Serialización nativa de objetos Java.
- Modelo concurrente `Thread-per-Connection`.
- `synchronized` para proteger sesiones activas.
- `ReentrantReadWriteLock` para proteger grupos y membresías.
- Manejo de desconexiones abruptas mediante bloques `try-catch-finally`.

## Funcionalidades principales

El sistema implementa dos funciones principales:

1. **Mensajería privada**
   - Permite enviar mensajes uno a uno entre clientes conectados.
   - El remitente solo necesita conocer el identificador lógico del destinatario.
   - El servidor resuelve el destinatario mediante `SessionManager`.

2. **Mensajería grupal**
   - Permite crear grupos, unirse a ellos y enviar mensajes grupales.
   - El servidor obtiene los miembros del grupo mediante `GroupManager`.
   - El mensaje se replica hacia los miembros conectados mediante broadcast controlado.

## Funcionalidades disponibles

- Inicio de sesión con identificador lógico de usuario.
- Envío de mensajes privados.
- Creación de grupos.
- Unión a grupos existentes.
- Envío de mensajes grupales.
- Cierre voluntario de sesión.
- Manejo básico de errores cuando un destinatario no está conectado o un grupo no existe.

## Estructura del proyecto

```text
Aplicacion-WhatsApp/
├── pom.xml
├── README.md
├── src/
│   └── main/
│       └── java/
│           └── whatsapp/
│               ├── client/
│               │   └── ClienteNodo.java
│               ├── common/
│               │   └── models/
│               │       ├── PaqueteRed.java
│               │       ├── PaqueteLogin.java
│               │       ├── PaqueteLogout.java
│               │       ├── PaqueteMensaje.java
│               │       ├── PaqueteCrearGrupo.java
│               │       ├── PaqueteUnirseGrupo.java
│               │       ├── PaqueteConfirm.java
│               │       └── PaqueteError.java
│               └── server/
│                   ├── core/
│                   │   └── ServidorPrincipal.java
│                   ├── handlers/
│                   │   └── ManejadorCliente.java
│                   └── managers/
│                       ├── SessionManager.java
│                       └── GroupManager.java
└── docs/
    └── diagrams/
        ├── puml/
        └── img/
