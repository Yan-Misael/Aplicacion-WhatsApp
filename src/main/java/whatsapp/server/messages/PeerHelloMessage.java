package whatsapp.server.messages;

import whatsapp.server.node.NodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mensaje de presentación inicial entre nodos.
 *
 * <p>Este mensaje permite que un {@code ServerNode} informe a otro nodo su
 * identidad, puertos y peers conocidos.</p>
 *
 * <p>La implementación real de envío y recepción será responsabilidad de la
 * Persona 2. Esta clase solo define el contrato serializable del mensaje.</p>
 */
public class PeerHelloMessage extends NodeMessage {

    private static final long serialVersionUID = 1L;

    private final NodeInfo nodeInfo;
    private final List<NodeInfo> knownPeers;

    /**
     * Crea un mensaje {@code PEER_HELLO}.
     *
     * @param sourceNodeId nodo que envía la presentación
     * @param targetNodeId nodo que recibe la presentación
     * @param lamportTimestamp timestamp lógico asociado
     * @param nodeInfo información del nodo emisor
     * @param knownPeers lista de peers conocidos por el emisor
     */
    public PeerHelloMessage(
            String sourceNodeId,
            String targetNodeId,
            long lamportTimestamp,
            NodeInfo nodeInfo,
            List<NodeInfo> knownPeers
    ) {
        super(sourceNodeId, targetNodeId, NodeMessageType.PEER_HELLO, lamportTimestamp);
        this.nodeInfo = nodeInfo;
        this.knownPeers = knownPeers == null
                ? new ArrayList<>()
                : new ArrayList<>(knownPeers);
    }

    /**
     * @return información del nodo emisor
     */
    public NodeInfo getNodeInfo() {
        return nodeInfo;
    }

    /**
     * @return lista inmodificable de peers conocidos por el emisor
     */
    public List<NodeInfo> getKnownPeers() {
        return Collections.unmodifiableList(knownPeers);
    }
}