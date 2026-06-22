package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
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
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    public HeartbeatEmitterTask(
            String selfNodeId,
            MembershipManager membershipManager,
            PeerTransport peerTransport,
            LamportClock lamportClock,
            EventLogger eventLogger
    ) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    @Override
    public void run() {
        try {
            for (NodeInfo target : membershipManager.getAliveNodes()) {
                if (target.getNodeId().equals(selfNodeId)) {
                    continue;
                }

                long ts = lamportClock.tick();
                HeartbeatMessage hb = new HeartbeatMessage(selfNodeId, target.getNodeId(), ts);
                eventLogger.logSend("HEARTBEAT", selfNodeId + "→" + target.getNodeId(), ts);
                peerTransport.sendToNode(target.getNodeId(), hb);
            }
        } catch (Exception e) {
            System.err.println("[" + selfNodeId + "] Error en el emisor de latidos: " + e.getMessage());
        }
    }
}