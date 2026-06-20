package whatsapp.server.peer;

import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.HeartbeatMessage;
import whatsapp.server.node.NodeInfo;

/**
 * Tarea programada que emite latidos (Heartbeats) periódicamente a todos
 * los peers conocidos que se consideran vivos.
 */
public class HeartbeatEmitterTask implements Runnable {
    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final PeerTransport peerTransport;

    public HeartbeatEmitterTask(String selfNodeId, MembershipManager membershipManager, PeerTransport peerTransport) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
    }

    @Override
    public void run() {
        try {
            // Obtiene solo los nodos vivos para no saturar la red intentando
            // conectar con nodos que ya sabe que están caídos.
            for (NodeInfo target : membershipManager.getAliveNodes()) {
                // Evita enviar un latido al mismo nodo emisor
                if (target.getNodeId().equals(selfNodeId)) {
                    continue;
                }
                
                // Por ahora envia 0L para no romper la firma.
                // parte de Lamport (Persona 4) 
                HeartbeatMessage hb = new HeartbeatMessage(selfNodeId, target.getNodeId(), 0L);

                // Delega el envío al transporte inter-nodo
                peerTransport.sendToNode(target.getNodeId(), hb);
                
                // Si necesitan ver en consola que está funcionando, descomenten esto,
                // pero recuerden comentarlo de nuevo para la demo o ensuciará los logs.
                // System.out.println("[" + selfNodeId + "] Latido emitido hacia " + target.getNodeId());
            }
        } catch (Exception e) {
            // Si una excepción no atrapada sale del run(), 
            // el ScheduledExecutorService de Java matará el hilo permanentemente.
            System.err.println("[" + selfNodeId + "] Error en el emisor de latidos: " + e.getMessage());
        }
    }
}