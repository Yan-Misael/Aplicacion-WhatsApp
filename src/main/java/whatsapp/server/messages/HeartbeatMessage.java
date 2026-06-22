package whatsapp.server.messages;

/**
 * Representa un latido periódico enviado entre nodos para confirmar
 * que siguen operativos y la conexión de red es estable.
 */
public class HeartbeatMessage extends NodeMessage {
    private static final long serialVersionUID = 1L;

    public HeartbeatMessage(String sourceNodeId, String targetNodeId, long lamportTimestamp) {
        // Usa el tipo HEARTBEAT que ya está definido en NodeMessageType
        super(sourceNodeId, targetNodeId, NodeMessageType.HEARTBEAT, lamportTimestamp);
    }
}