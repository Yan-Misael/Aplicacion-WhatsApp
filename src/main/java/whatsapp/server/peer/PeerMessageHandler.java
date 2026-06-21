package whatsapp.server.peer;

import whatsapp.server.membership.MembershipManager;
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
                log("Mensaje inválido recibido desde " + socket.getRemoteSocketAddress());
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
                // ---- NUEVO: manejar mensajes de aplicación ----
                case APP:
                    AppMessage appMsg = (AppMessage) message;
                    handleAppMessage(appMsg);
                    break;
                default:
                    // Mensajes futuros (Persona 3, 4, 5) — registrar y delegar
                    log("Tipo de mensaje pendiente de implementación: " + message.getType()
                            + " desde " + message.getSourceNodeId());
                    connectionManager.enqueueIncoming(message);
                    break;
            }

        } catch (IOException | ClassNotFoundException e) {
            log("Error procesando mensaje de peer: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Manejadores específicos
    // -------------------------------------------------------------------------

    private void handlePeerHello(PeerHelloMessage hello, ObjectOutputStream out) throws IOException {
        String sourceId = hello.getSourceNodeId();
        log("PEER_HELLO recibido desde " + sourceId);

        NodeInfo senderInfo = hello.getNodeInfo();
        if (senderInfo != null) {
            membershipManager.addOrUpdateNode(senderInfo);
            log("Peer registrado: " + senderInfo);
        }

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
    // NUEVO: manejo de mensajes de aplicación (APP)
    // -------------------------------------------------------------------------

    private void handleAppMessage(AppMessage appMsg) {
        PaqueteRed payload = appMsg.getPayload();

        if (payload instanceof PaqueteMensaje) {
            PaqueteMensaje pm = (PaqueteMensaje) payload;
            String grupo = pm.getIdDestinatario();
            String remitente = pm.getIdRemitente();

            if (pm.isEsGrupo()) {
                // Mensaje grupal: entregar a miembros locales (excepto remitente)
                if (!distributedGroupManager.groupExists(grupo)) {
                    log("Mensaje grupal para grupo inexistente: " + grupo);
                    return;
                }
                Set<String> miembros = distributedGroupManager.getMembersSnapshot(grupo);
                for (String miembro : miembros) {
                    if (miembro.equals(remitente)) continue;
                    Optional<ManejadorCliente> cli = localSessionManager.getLocalSession(miembro);
                    cli.ifPresent(c -> {
                        try {
                            c.enviarObjeto(pm);
                            log("Mensaje grupal entregado localmente a " + miembro);
                        } catch (IOException e) {
                            log("Error entregando mensaje grupal a " + miembro + ": " + e.getMessage());
                        }
                    });
                }
            } else {
                // Mensaje privado: entregar al destinatario local
                String destino = pm.getIdDestinatario();
                Optional<ManejadorCliente> dest = localSessionManager.getLocalSession(destino);
                if (dest.isPresent()) {
                    try {
                        dest.get().enviarObjeto(pm);
                        log("Mensaje privado entregado localmente a " + destino);
                    } catch (IOException e) {
                        log("Error entregando mensaje privado a " + destino + ": " + e.getMessage());
                    }
                } else {
                    log("Destinatario local no encontrado: " + destino);
                }
            }
        }
        else if (payload instanceof PaqueteCrearGrupo) {
            PaqueteCrearGrupo crear = (PaqueteCrearGrupo) payload;
            String grupo = crear.getIdGrupo();
            String creador = crear.getIdRemitente();
            boolean creado = distributedGroupManager.createGroup(grupo, Set.of(creador));
            if (creado) {
                log("Grupo creado remotamente: " + grupo + " por " + creador);
            } else {
                log("Intento de crear grupo ya existente: " + grupo);
            }
        }
        else if (payload instanceof PaqueteUnirseGrupo) {
            PaqueteUnirseGrupo unirse = (PaqueteUnirseGrupo) payload;
            String grupo = unirse.getIdGrupo();
            String usuario = unirse.getIdRemitente();
            boolean agregado = distributedGroupManager.addMember(grupo, usuario);
            if (agregado) {
                log("Usuario " + usuario + " se unió al grupo " + grupo + " (desde otro nodo)");
            } else {
                log("Fallo al unir usuario " + usuario + " al grupo " + grupo + " (remoto)");
            }
        }
        else {
            log("Payload no reconocido en AppMessage: " + payload.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][PeerMsgHandler] %s%n", selfNodeId, msg);
    }
}