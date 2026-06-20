package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.HeartbeatMessage;
import whatsapp.server.messages.MembershipUpdateMessage;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.PeerHelloAckMessage;
import whatsapp.server.messages.PeerHelloMessage;
import whatsapp.server.node.NodeInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Procesa un mensaje recibido desde otro nodo servidor.
 *
 * <p>Cada instancia maneja una única conexión entrante de un peer. Lee el
 * {@link NodeMessage}, actualiza la membresía y despacha según el tipo.
 * Los mensajes que corresponden a Persona 3, 4 o 5 se registran y se descartan
 * sin error para mantener compatibilidad futura.</p>
 *
 * <p><b>Protocolo de handshake ObjectStream:</b> Para evitar el deadlock clásico
 * de Java Object serialization, AMBOS lados deben crear su ObjectOutputStream
 * y hacer flush ANTES de crear el ObjectInputStream. El emisor
 * (PeerConnectionManager#doSendAndReadAck) ya lo hace. Este receptor también
 * debe seguir el mismo orden: OOS + flush → OIS → readObject.</p>
 */
public class PeerMessageHandler implements Runnable {

    private final Socket socket;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;
    private final String selfNodeId;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    public PeerMessageHandler(
            Socket socket,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            String selfNodeId,
            LamportClock lamportClock,
            EventLogger eventLogger
    ) {
        this.socket = socket;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.selfNodeId = selfNodeId;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);

            // CRÍTICO: crear ObjectOutputStream primero y hacer flush ANTES de
            // crear ObjectInputStream. Esto es obligatorio en Java para evitar
            // el deadlock del handshake de headers:
            //   - El emisor (doSendAndReadAck) crea OOS → flush → OIS
            //   - El receptor (aquí) debe hacer lo mismo: OOS → flush → OIS
            // Si cualquiera de los dos crea OIS antes de que el otro haya
            // enviado su header via OOS+flush, ambos quedan bloqueados
            // esperando el header del otro → SocketTimeoutException al expirar.
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush(); // enviar header inmediatamente

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream()); // recibir header del emisor

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

                default:
                    // Mensajes futuros (Persona 3, 4, 5) — registrar y delegar
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
    // Manejadores específicos
    // -------------------------------------------------------------------------

    private void handlePeerHello(PeerHelloMessage hello, ObjectOutputStream out) throws IOException {
        String sourceId = hello.getSourceNodeId();

        log("PEER_HELLO recibido desde " + sourceId);

        // Registrar o actualizar el nodo emisor en la membresía
        NodeInfo senderInfo = hello.getNodeInfo();
        if (senderInfo != null) {
            membershipManager.addOrUpdateNode(senderInfo);
            log("Peer registrado/actualizado: " + senderInfo);
        }

        // Registrar peers adicionales que el emisor conoce
        for (NodeInfo peer : hello.getKnownPeers()) {
            if (!peer.getNodeId().equals(selfNodeId)) {
                membershipManager.addOrUpdateNode(peer);
            }
        }

        // Responder con PEER_HELLO_ACK en la misma conexión
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
        // La detección de fallos funciona por lastSeenMillis (actualizado en run() con touch()).
        // No se envía ACK porque doSend usa fire-and-forget y ya cierra el socket antes
        // de que el receptor pueda escribir una respuesta — escribir aquí provocaba Broken pipe.
        log("HEARTBEAT recibido de " + hb.getSourceNodeId() + " L=" + hb.getLamportTimestamp());
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][PeerMsgHandler] %s%n", selfNodeId, msg);
    }
}
