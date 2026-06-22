package whatsapp.server.membership;

import whatsapp.server.node.NodeInfo;
import whatsapp.server.node.NodeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administra la membresía conocida por un {@code ServerNode}.
 *
 * <p>La membresía representa qué nodos conoce el nodo actual y cuál es el estado
 * lógico asociado a cada uno. Esta clase deja la base para que Persona 2 implemente
 * comunicación real entre nodos y Persona 5 agregue heartbeats/timeouts.</p>
 */
public class MembershipManager {

    private final NodeInfo self;
    private final ConcurrentHashMap<String, NodeInfo> nodesById = new ConcurrentHashMap<>();

    /**
     * Crea un administrador de membresía.
     *
     * @param self información del nodo actual
     * @param initialPeers peers iniciales configurados
     */
    public MembershipManager(NodeInfo self, List<NodeInfo> initialPeers) {
        this.self = self;

        if (initialPeers != null) {
            for (NodeInfo peer : initialPeers) {
                if (!peer.getNodeId().equals(self.getNodeId())) {
                    nodesById.put(peer.getNodeId(), peer);
                }
            }
        }
    }

    /**
     * @return información del nodo actual
     */
    public NodeInfo getSelf() {
        return self;
    }

    /**
     * Agrega un nodo nuevo o actualiza uno existente.
     *
     * @param nodeInfo nodo a registrar o actualizar
     */
    public void addOrUpdateNode(NodeInfo nodeInfo) {
        if (nodeInfo == null || nodeInfo.getNodeId().equals(self.getNodeId())) {
            return;
        }

        nodesById.put(nodeInfo.getNodeId(), nodeInfo);
    }

    /**
     * Busca un nodo por su identificador lógico.
     *
     * @param nodeId identificador lógico del nodo
     * @return nodo encontrado, si existe
     */
    public Optional<NodeInfo> getNode(String nodeId) {
        return Optional.ofNullable(nodesById.get(nodeId));
    }

    /**
     * @return lista de todos los nodos conocidos
     */
    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodesById.values());
    }

    /**
     * @return lista de nodos marcados como vivos
     */
    public List<NodeInfo> getAliveNodes() {
        List<NodeInfo> alive = new ArrayList<>();

        for (NodeInfo node : nodesById.values()) {
            if (node.getStatus() == NodeStatus.ALIVE) {
                alive.add(node);
            }
        }

        return alive;
    }

    /**
     * Marca un nodo como disponible.
     *
     * @param nodeId identificador del nodo
     */
    public void markAlive(String nodeId) {
        updateStatus(nodeId, NodeStatus.ALIVE);
    }

    /**
     * Marca un nodo como sospechoso.
     *
     * @param nodeId identificador del nodo
     */
    public void markSuspected(String nodeId) {
        updateStatus(nodeId, NodeStatus.SUSPECTED);
    }

    /**
     * Marca un nodo como caído.
     *
     * @param nodeId identificador del nodo
     */
    public void markDown(String nodeId) {
        updateStatus(nodeId, NodeStatus.DOWN);
    }

    /**
     * Actualiza el estado de un nodo si existe en la membresía.
     *
     * @param nodeId identificador del nodo
     * @param status nuevo estado
     */
    private void updateStatus(String nodeId, NodeStatus status) {
        NodeInfo node = nodesById.get(nodeId);

        if (node != null) {
            node.setStatus(status);
        }
    }
}