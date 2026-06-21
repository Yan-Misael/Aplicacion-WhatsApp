package whatsapp.server.messages;

/**
 * Mensaje de elección de coordinador (algoritmo Bully).
 *
 * <p>Sirve para los tres tipos del protocolo:</p>
 * <ul>
 *   <li>{@link NodeMessageType#ELECTION} — candidato pregunta a nodos con ID mayor</li>
 *   <li>{@link NodeMessageType#ELECTION_OK} — nodo mayor responde que participará</li>
 *   <li>{@link NodeMessageType#ELECTION_COORDINATOR} — nuevo coordinador se anuncia;
 *       en este caso {@code coordinatorId} lleva el ID del coordinador electo</li>
 * </ul>
 */
public class ElectionMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    /** Solo presente en mensajes de tipo ELECTION_COORDINATOR. */
    private final String coordinatorId;

    /** Constructor para ELECTION y ELECTION_OK (sin coordinador). */
    public ElectionMessage(String sourceNodeId, String targetNodeId,
                           NodeMessageType type, long lamportTimestamp) {
        super(sourceNodeId, targetNodeId, type, lamportTimestamp);
        this.coordinatorId = null;
    }

    /** Constructor para ELECTION_COORDINATOR. */
    public ElectionMessage(String sourceNodeId, String targetNodeId,
                           long lamportTimestamp, String coordinatorId) {
        super(sourceNodeId, targetNodeId, NodeMessageType.ELECTION_COORDINATOR, lamportTimestamp);
        this.coordinatorId = coordinatorId;
    }

    /** @return ID del coordinador electo; {@code null} en mensajes no-COORDINATOR */
    public String getCoordinatorId() {
        return coordinatorId;
    }
}
