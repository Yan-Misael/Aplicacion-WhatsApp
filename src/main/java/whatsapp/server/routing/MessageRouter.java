package whatsapp.server.routing;

import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.peer.PeerTransport;

import java.util.Optional;

/**
 * Componente encargado de decidir si un mensaje debe entregarse localmente o
 * reenviarse hacia otro nodo.
 *
 * <p>Esta clase deja la base arquitectónica del enrutamiento distribuido. La lógica
 * final de mensajes privados y grupales será completada por Persona 3 usando el
 * transporte real implementado por Persona 2.</p>
 */
public class MessageRouter {

    private final String selfNodeId;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;
    private final MembershipManager membershipManager;
    private final PeerTransport peerTransport;

    /**
     * Crea un router de mensajes.
     *
     * @param selfNodeId identificador del nodo local
     * @param globalUserDirectory directorio usuario-nodo
     * @param distributedGroupManager administrador distribuido de grupos
     * @param membershipManager administrador de membresía
     * @param peerTransport transporte inter-nodo
     */
    public MessageRouter(
            String selfNodeId,
            GlobalUserDirectory globalUserDirectory,
            DistributedGroupManager distributedGroupManager,
            MembershipManager membershipManager,
            PeerTransport peerTransport
    ) {
        this.selfNodeId = selfNodeId;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
    }

    /**
     * Indica si un usuario destino pertenece al nodo local.
     *
     * @param userId identificador del usuario destino
     * @return {@code true} si el usuario está registrado en el nodo local
     */
    public boolean isLocalDestination(String userId) {
        Optional<String> node = globalUserDirectory.findNodeForUser(userId);
        return node.isPresent() && node.get().equals(selfNodeId);
    }

    /**
     * Resuelve el nodo donde se encuentra un usuario.
     *
     * @param userId identificador del usuario
     * @return nodo donde está conectado, si se conoce
     */
    public Optional<String> resolveUserNode(String userId) {
        return globalUserDirectory.findNodeForUser(userId);
    }

    /**
     * Método placeholder para representar el flujo futuro de mensaje privado.
     *
     * <p>Este método no entrega mensajes reales. Solo documenta, mediante logs,
     * la decisión de enrutamiento local o remoto. Persona 3 deberá reemplazar este
     * placeholder por lógica funcional.</p>
     *
     * @param fromUserId usuario emisor
     * @param toUserId usuario destinatario
     * @param content contenido del mensaje
     */
    public void routePrivateMessagePlaceholder(String fromUserId, String toUserId, String content) {
        Optional<String> targetNode = resolveUserNode(toUserId);

        if (targetNode.isEmpty()) {
            System.out.printf("[%s] Usuario destino desconocido: %s%n", selfNodeId, toUserId);
            return;
        }

        if (targetNode.get().equals(selfNodeId)) {
            System.out.printf("[%s] Entrega local pendiente de integrar: %s -> %s%n",
                    selfNodeId, fromUserId, toUserId);
        } else {
            System.out.printf("[%s] Reenvío remoto pendiente de Persona 2/3: %s -> %s vía %s%n",
                    selfNodeId, fromUserId, toUserId, targetNode.get());
        }
    }
}