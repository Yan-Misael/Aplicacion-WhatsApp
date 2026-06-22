package whatsapp.server.messages;

/**
 * Respuesta de permiso de acceso a sección crítica (Ricart-Agrawala).
 */
public class MutexReplyMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    public MutexReplyMessage(String sourceNodeId, String targetNodeId, long lamportTimestamp) {
        super(sourceNodeId, targetNodeId, NodeMessageType.MUTEX_REPLY, lamportTimestamp);
    }
}
