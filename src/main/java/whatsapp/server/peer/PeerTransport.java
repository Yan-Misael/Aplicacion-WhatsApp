package whatsapp.server.peer;

import whatsapp.server.messages.NodeMessage;

/**
 * Interfaz de transporte entre nodos.
 *
 * <p>Esta interfaz desacopla la arquitectura base del mecanismo concreto de red.
 * Persona 1 deja el contrato. Persona 2 debe implementar una versión TCP real
 * usando sockets.</p>
 */
public interface PeerTransport {

    /**
     * Envía un mensaje a un nodo específico.
     *
     * @param targetNodeId identificador del nodo destino
     * @param message mensaje a enviar
     */
    void sendToNode(String targetNodeId, NodeMessage message);

    /**
     * Envía un mensaje a todos los peers conocidos.
     *
     * @param message mensaje a difundir
     */
    void broadcast(NodeMessage message);

    /**
     * Inicia el transporte.
     *
     * <p>En la implementación real, este método debería levantar listeners o
     * recursos de red.</p>
     */
    void start();

    /**
     * Detiene el transporte y libera recursos asociados.
     */
    void stop();

    /**
     * Extrae el siguiente mensaje entrante aún no procesado por tipos
     * reservados (PEER_HELLO, HEARTBEAT, MEMBERSHIP_UPDATE), o {@code null}
     * si no hay ninguno pendiente.
     *
     * <p><b>Fix Problema 3:</b> antes, esta cola solo era accesible casteando
     * a {@code TcpPeerTransport}, lo que rompía el diseño por interfaz para
     * Persona 3/4/5. Ahora el contrato vive aquí, en {@link PeerTransport},
     * así que {@code ServerNodeContext.getPeerTransport().pollIncoming()}
     * funciona sin cast y sin conocer la implementación concreta.</p>
     *
     * @return el siguiente mensaje pendiente, o {@code null}
     */
    NodeMessage pollIncoming();
}