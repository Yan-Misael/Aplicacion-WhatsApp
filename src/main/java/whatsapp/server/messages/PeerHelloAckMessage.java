package whatsapp.server.messages;

import whatsapp.server.node.NodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mensaje de confirmación ante un {@link PeerHelloMessage}.
 *
 * <p>Permite indicar si la presentación de un nodo fue aceptada y devolver al
 * emisor información del nodo receptor junto con su lista de peers conocidos.</p>
 */
public class PeerHelloAckMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    private final boolean accepted;
    private final NodeInfo receiverNodeInfo;
    private final List<NodeInfo> knownPeers;

    /**
     * Crea un mensaje {@code PEER_HELLO_ACK}.
     *
     * @param sourceNodeId nodo que responde
     * @param targetNodeId nodo que recibirá el ACK
     * @param lamportTimestamp timestamp lógico asociado
     * @param accepted indica si el nodo fue aceptado
     * @param receiverNodeInfo información del nodo que responde
     * @param knownPeers lista de peers conocidos por el nodo que responde
     */
    public PeerHelloAckMessage(
            String sourceNodeId,
            String targetNodeId,
            long lamportTimestamp,
            boolean accepted,
            NodeInfo receiverNodeInfo,
            List<NodeInfo> knownPeers
    ) {
        super(sourceNodeId, targetNodeId, NodeMessageType.PEER_HELLO_ACK, lamportTimestamp);
        this.accepted = accepted;
        this.receiverNodeInfo = receiverNodeInfo;
        this.knownPeers = knownPeers == null
                ? new ArrayList<>()
                : new ArrayList<>(knownPeers);
    }

    /**
     * @return {@code true} si el peer fue aceptado
     */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * @return información del nodo receptor que confirma el saludo
     */
    public NodeInfo getReceiverNodeInfo() {
        return receiverNodeInfo;
    }

    /**
     * @return lista inmodificable de peers conocidos por el receptor
     */
    public List<NodeInfo> getKnownPeers() {
        return Collections.unmodifiableList(knownPeers);
    }
}