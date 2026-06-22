package whatsapp.server.messages;

import whatsapp.server.node.NodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mensaje de actualización de membresía entre nodos.
 *
 * <p>Permite propagar cambios en el conjunto de nodos conocidos, incluyendo
 * llegadas, salidas y cambios de estado. Persona 5 puede usarlo para informar
 * sobre nodos caídos o recuperados.</p>
 */
public class MembershipUpdateMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    private final List<NodeInfo> nodes;
    private final String reason;

    /**
     * Crea un mensaje de actualización de membresía.
     *
     * @param sourceNodeId    nodo que emite la actualización
     * @param targetNodeId    nodo destino ("*" para broadcast)
     * @param lamportTimestamp timestamp lógico de Lamport
     * @param nodes           lista de nodos a propagar
     * @param reason          motivo de la actualización (informativo)
     */
    public MembershipUpdateMessage(
            String sourceNodeId,
            String targetNodeId,
            long lamportTimestamp,
            List<NodeInfo> nodes,
            String reason
    ) {
        super(sourceNodeId, targetNodeId, NodeMessageType.MEMBERSHIP_UPDATE, lamportTimestamp);
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        this.reason = reason == null ? "" : reason;
    }

    /**
     * @return lista inmodificable de nodos incluidos en la actualización
     */
    public List<NodeInfo> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * @return motivo de la actualización
     */
    public String getReason() {
        return reason;
    }
}
