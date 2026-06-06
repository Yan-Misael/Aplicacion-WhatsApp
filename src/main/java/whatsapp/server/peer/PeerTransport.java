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
}