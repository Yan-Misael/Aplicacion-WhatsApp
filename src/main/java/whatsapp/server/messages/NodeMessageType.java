package whatsapp.server.messages;

/**
 * Enumera los tipos de mensajes que pueden intercambiarse entre nodos servidores.
 *
 * <p>Estos mensajes no corresponden a mensajes cliente-servidor, sino a mensajes
 * servidor-servidor utilizados por la arquitectura multiservidor.</p>
 */
public enum NodeMessageType {

    /**
     * Mensaje de presentación inicial entre nodos.
     */
    PEER_HELLO,

    /**
     * Confirmación de recepción de {@code PEER_HELLO}.
     */
    PEER_HELLO_ACK,

    /**
     * Actualización de información de membresía.
     */
    MEMBERSHIP_UPDATE,

    /**
     * Anuncia que un usuario inició sesión en un nodo.
     */
    USER_LOGIN_ANNOUNCE,

    /**
     * Anuncia que un usuario cerró sesión o se desconectó.
     */
    USER_LOGOUT_ANNOUNCE,

    /**
     * Consulta en qué nodo está conectado un usuario.
     */
    USER_LOCATION_QUERY,

    /**
     * Respuesta a una consulta de ubicación de usuario.
     */
    USER_LOCATION_RESPONSE,

    /**
     * Reenvío de mensaje privado hacia otro nodo.
     */
    PRIVATE_MESSAGE_FORWARD,

    /**
     * Confirmación de recepción o entrega de mensaje privado remoto.
     */
    PRIVATE_MESSAGE_ACK,

    /**
     * Reenvío de mensaje grupal hacia otro nodo.
     */
    GROUP_MESSAGE_FORWARD,

    /**
     * Confirmación de recepción o entrega de mensaje grupal remoto.
     */
    GROUP_MESSAGE_ACK,

    /**
     * Solicitud lógica de creación de grupo.
     */
    GROUP_CREATE_REQUEST,

    /**
     * Solicitud lógica para unir un usuario a un grupo.
     */
    GROUP_JOIN_REQUEST,

    /**
     * Solicitud lógica para retirar un usuario de un grupo.
     */
    GROUP_LEAVE_REQUEST,

    /**
     * Confirmación distribuida de una actualización de grupo.
     */
    GROUP_UPDATE_COMMIT,

    /**
     * ACK de actualización de grupo aplicada.
     */
    GROUP_UPDATE_ACK,

    /**
     * Solicitud de entrada a sección crítica distribuida.
     */
    MUTEX_REQUEST,

    /**
     * Respuesta positiva o diferida para sección crítica distribuida.
     */
    MUTEX_REPLY,

    /**
     * Mensaje opcional para indicar salida de sección crítica.
     */
    MUTEX_RELEASE,

    /**
     * Señal periódica de vida entre nodos.
     */
    HEARTBEAT,

    /**
     * Confirmación de recepción de heartbeat.
     */
    HEARTBEAT_ACK,

    APP, // Mensaje de aplicación con payload (como PaqueteMensaje) entre nodos.

    /**
     * Mensaje genérico de error entre nodos.
     */
    NODE_ERROR,

    /**
     * Inicia una elección de coordinador (Bully): enviado a nodos con ID mayor.
     */
    ELECTION,

    /**
     * Respuesta a ELECTION: indica que el respondedor tiene ID mayor y participará.
     */
    ELECTION_OK,

    /**
     * Anuncia el resultado de la elección: el nuevo coordinador se presenta.
     */
    ELECTION_COORDINATOR
}