package whatsapp.server.peer;

import whatsapp.server.messages.NodeMessage;

/**
 * Implementación temporal de {@link PeerTransport} que no realiza comunicación
 * real por red.
 *
 * <p>Esta clase sirve como placeholder de arquitectura. Permite compilar e iniciar
 * un {@code ServerNode} sin implementar todavía sockets inter-nodo.</p>
 *
 * <p>Persona 2 debe reemplazar esta implementación por una basada en TCP.</p>
 */
public class NoOpPeerTransport implements PeerTransport {

    private final String nodeId;

    /**
     * Crea un transporte no operativo asociado a un nodo.
     *
     * @param nodeId identificador del nodo local
     */
    public NoOpPeerTransport(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * Simula el envío de un mensaje a otro nodo mediante log.
     *
     * @param targetNodeId nodo destino
     * @param message mensaje a enviar
     */
    @Override
    public void sendToNode(String targetNodeId, NodeMessage message) {
        System.out.printf(
                "[%s] NO_OP sendToNode target=%s type=%s messageId=%s%n",
                nodeId,
                targetNodeId,
                message.getType(),
                message.getMessageId()
        );
    }

    /**
     * Simula la difusión de un mensaje mediante log.
     *
     * @param message mensaje a difundir
     */
    @Override
    public void broadcast(NodeMessage message) {
        System.out.printf(
                "[%s] NO_OP broadcast type=%s messageId=%s%n",
                nodeId,
                message.getType(),
                message.getMessageId()
        );
    }

    /**
     * Simula el inicio del transporte.
     */
    @Override
    public void start() {
        System.out.printf("[%s] NO_OP peer transport iniciado%n", nodeId);
    }

    /**
     * Simula la detención del transporte.
     */
    @Override
    public void stop() {
        System.out.printf("[%s] NO_OP peer transport detenido%n", nodeId);
    }
}