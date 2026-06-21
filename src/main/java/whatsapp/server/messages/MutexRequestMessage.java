package whatsapp.server.messages;

/**
 * Solicitud de entrada a sección crítica distribuida (Ricart-Agrawala).
 *
 * <p>El campo {@code lamportTimestamp} heredado de {@link NodeMessage} es el
 * timestamp de la solicitud usado para desempate en el algoritmo.</p>
 */
public class MutexRequestMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    private final String resourceId;

    public MutexRequestMessage(String sourceNodeId, String targetNodeId,
                               long lamportTimestamp, String resourceId) {
        super(sourceNodeId, targetNodeId, NodeMessageType.MUTEX_REQUEST, lamportTimestamp);
        this.resourceId = resourceId;
    }

    public String getResourceId() {
        return resourceId;
    }
}
