package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.ElectionMessage;
import whatsapp.server.messages.HeartbeatMessage;
import whatsapp.server.messages.MembershipUpdateMessage;
import whatsapp.server.messages.MutexRequestMessage;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.messages.PeerHelloAckMessage;
import whatsapp.server.messages.PeerHelloMessage;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;
import whatsapp.server.node.NodeInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Procesa un mensaje recibido desde otro nodo servidor.
 *
 * <p>Cada instancia maneja una única conexión entrante de un peer. Lee el
 * {@link NodeMessage}, actualiza la membresía y despacha según el tipo.</p>
 *
 * <p><b>Protocolo de handshake ObjectStream:</b> Para evitar el deadlock clásico
 * de Java Object serialization, AMBOS lados deben crear su ObjectOutputStream
 * y hacer flush ANTES de crear el ObjectInputStream.</p>
 */
public class PeerMessageHandler implements Runnable {

    private final Socket socket;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;
    private final String selfNodeId;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;
    private final RicartAgrawalaCoordinator ricartCoordinator;
    private final BullyElectionCoordinator bullyCoordinator;

    public PeerMessageHandler(
            Socket socket,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            String selfNodeId,
            LamportClock lamportClock,
            EventLogger eventLogger,
            RicartAgrawalaCoordinator ricartCoordinator,
            BullyElectionCoordinator bullyCoordinator
    ) {
        this.socket = socket;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.selfNodeId = selfNodeId;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
        this.ricartCoordinator = ricartCoordinator;
        this.bullyCoordinator = bullyCoordinator;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);

            // CRÍTICO: crear ObjectOutputStream primero y hacer flush ANTES de
            // crear ObjectInputStream para evitar el deadlock de headers.
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            Object raw = in.readObject();

            if (!(raw instanceof NodeMessage)) {
                log("Mensaje inválido recibido desde " + socket.getRemoteSocketAddress() + " — descartado");
                return;
            }

            NodeMessage message = (NodeMessage) raw;

            // Regla de Lamport al recibir: local = max(local, recibido) + 1
            long updatedTs = lamportClock.update(message.getLamportTimestamp());
            eventLogger.logReceive(
                    message.getType().name(),
                    message.getSourceNodeId() + "→" + selfNodeId,
                    updatedTs
            );

            // Actualizar lastSeen en membresía
            membershipManager.markAlive(message.getSourceNodeId());
            NodeInfo senderInfo = membershipManager.getNode(message.getSourceNodeId()).orElse(null);
            if (senderInfo != null) {
                senderInfo.touch();
            }

            log("Mensaje recibido: type=" + message.getType()
                    + " source=" + message.getSourceNodeId()
                    + " L=" + updatedTs);

            switch (message.getType()) {
                case PEER_HELLO:
                    handlePeerHello((PeerHelloMessage) message, out);
                    break;

                case MEMBERSHIP_UPDATE:
                    handleMembershipUpdate((MembershipUpdateMessage) message);
                    break;

                case HEARTBEAT:
                    handleHeartbeat(message);
                    break;

                // ---- Ricart-Agrawala ----
                case MUTEX_REQUEST:
                    handleMutexRequest((MutexRequestMessage) message);
                    break;

                case MUTEX_REPLY:
                    handleMutexReply(message);
                    break;

                // ---- Bully Election ----
                case ELECTION:
                    handleElection((ElectionMessage) message);
                    break;

                case ELECTION_OK:
                    handleElectionOk(message);
                    break;

                case ELECTION_COORDINATOR:
                    handleElectionCoordinator((ElectionMessage) message);
                    break;

                default:
                    // Mensajes futuros (Persona 3, 5) — registrar y encolar
                    log("Tipo de mensaje pendiente de implementación: " + message.getType()
                            + " desde " + message.getSourceNodeId());
                    connectionManager.enqueueIncoming(message);
                    break;
            }

        } catch (IOException | ClassNotFoundException e) {
            log("Error procesando mensaje de peer " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Manejadores de mensajes existentes
    // -------------------------------------------------------------------------

    private void handlePeerHello(PeerHelloMessage hello, ObjectOutputStream out) throws IOException {
        String sourceId = hello.getSourceNodeId();

        log("PEER_HELLO recibido desde " + sourceId);

        NodeInfo senderInfo = hello.getNodeInfo();
        if (senderInfo != null) {
            membershipManager.addOrUpdateNode(senderInfo);
            log("Peer registrado/actualizado: " + senderInfo);
        }

        for (NodeInfo peer : hello.getKnownPeers()) {
            if (!peer.getNodeId().equals(selfNodeId)) {
                membershipManager.addOrUpdateNode(peer);
            }
        }

        long ackTs = lamportClock.tick();
        PeerHelloAckMessage ack = new PeerHelloAckMessage(
                selfNodeId,
                sourceId,
                ackTs,
                true,
                membershipManager.getSelf(),
                membershipManager.getAllNodes()
        );

        out.writeObject(ack);
        out.flush();

        eventLogger.logSend("PEER_HELLO_ACK", selfNodeId + "→" + sourceId, ackTs);
        log("PEER_HELLO_ACK enviado a " + sourceId + " L=" + ackTs);
        log("Peer detectado: " + sourceId);
    }

    private void handleMembershipUpdate(MembershipUpdateMessage update) {
        log("MEMBERSHIP_UPDATE recibido desde " + update.getSourceNodeId()
                + " razón=" + update.getReason());

        for (NodeInfo node : update.getNodes()) {
            if (!node.getNodeId().equals(selfNodeId)) {
                membershipManager.addOrUpdateNode(node);
                log("Membresía actualizada: " + node);
            }
        }
    }

    private void handleHeartbeat(NodeMessage hb) {
        log("HEARTBEAT recibido de " + hb.getSourceNodeId() + " L=" + hb.getLamportTimestamp());
    }

    // -------------------------------------------------------------------------
    // Manejadores de Ricart-Agrawala
    // -------------------------------------------------------------------------

    private void handleMutexRequest(MutexRequestMessage req) {
        log("MUTEX_REQUEST de " + req.getSourceNodeId()
                + " recurso=" + req.getResourceId()
                + " L=" + req.getLamportTimestamp());
        ricartCoordinator.onMutexRequest(req.getSourceNodeId(), req.getLamportTimestamp());
    }

    private void handleMutexReply(NodeMessage reply) {
        log("MUTEX_REPLY de " + reply.getSourceNodeId() + " L=" + reply.getLamportTimestamp());
        ricartCoordinator.onMutexReply(reply.getSourceNodeId());
    }

    // -------------------------------------------------------------------------
    // Manejadores de Bully Election
    // -------------------------------------------------------------------------

    private void handleElection(ElectionMessage msg) {
        String senderId = msg.getSourceNodeId();
        if (senderId.compareTo(selfNodeId) < 0) {
            // Solo respondemos si el remitente tiene ID menor (correcto para Bully)
            log("ELECTION de " + senderId + " (ID menor) — respondiendo OK");
            bullyCoordinator.onElectionMessage(senderId);
        } else {
            log("ELECTION de " + senderId + " (ID mayor o igual) — ignorado");
        }
    }

    private void handleElectionOk(NodeMessage msg) {
        log("ELECTION_OK de " + msg.getSourceNodeId());
        bullyCoordinator.onElectionOk(msg.getSourceNodeId());
    }

    private void handleElectionCoordinator(ElectionMessage msg) {
        String newCoordinator = msg.getCoordinatorId() != null
                ? msg.getCoordinatorId()
                : msg.getSourceNodeId();
        log("ELECTION_COORDINATOR: nuevo coordinador = " + newCoordinator);
        bullyCoordinator.onCoordinatorAnnouncement(newCoordinator);
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][PeerMsgHandler] %s%n", selfNodeId, msg);
    }
}
