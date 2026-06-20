package whatsapp.server.peer;

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

    public PeerMessageHandler(
            Socket socket,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            String selfNodeId
    ) {
        this.socket = socket;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.selfNodeId = selfNodeId;
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

            // Actualizar lastSeen en membresía
            membershipManager.markAlive(message.getSourceNodeId());
            NodeInfo senderInfo = membershipManager.getNode(message.getSourceNodeId()).orElse(null);
            if (senderInfo != null) {
                senderInfo.touch();
            }

            log("Mensaje recibido: type=" + message.getType()
                    + " source=" + message.getSourceNodeId()
                    + " L=" + message.getLamportTimestamp());

            switch (message.getType()) {
                case PEER_HELLO:
                    handlePeerHello((PeerHelloMessage) message, out);
                    break;

                case MEMBERSHIP_UPDATE:
                    handleMembershipUpdate((MembershipUpdateMessage) message);
                    break;

                case HEARTBEAT:
                    handleHeartbeat(message, out);
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
        PeerHelloAckMessage ack = new PeerHelloAckMessage(
                selfNodeId,
                sourceId,
                0L, // Lamport se completará por Persona 4
                true,
                membershipManager.getSelf(),
                membershipManager.getAllNodes()
        );

        out.writeObject(ack);
        out.flush();

        log("PEER_HELLO_ACK enviado a " + sourceId);
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

    private void handleHeartbeat(NodeMessage hb, ObjectOutputStream out) throws IOException {
        // Envia el ACK de vuelta al emisor para que no lo declare muerto
        HeartbeatMessage ack = new HeartbeatMessage(
                selfNodeId, 
                hb.getSourceNodeId(), 
                0L // Lamport placeholder
        );
        
        // Transformamos temporalmente el tipo del NodeMessage para que sea un ACK.
        // Lo ideal será crear un HeartbeatAckMessage o agregar un flag boolean isAck al HeartbeatMessage.
        // Pero asumiendo que usarán el NodeMessageType.HEARTBEAT_ACK:
        
        out.writeObject(new NodeMessage(selfNodeId, hb.getSourceNodeId(), whatsapp.server.messages.NodeMessageType.HEARTBEAT_ACK, 0L) {
            private static final long serialVersionUID = 1L;
        });
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][PeerMsgHandler] %s%n", selfNodeId, msg);
    }
}
