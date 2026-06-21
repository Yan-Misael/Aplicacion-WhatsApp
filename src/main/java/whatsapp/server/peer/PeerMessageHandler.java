package whatsapp.server.peer;

import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.common.models.PaqueteRed;
import whatsapp.common.models.PaqueteUnirseGrupo;
import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.AppMessage;
import whatsapp.server.messages.ElectionMessage;
import whatsapp.server.messages.MembershipUpdateMessage;
import whatsapp.server.messages.MutexRequestMessage;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.messages.PeerHelloAckMessage;
import whatsapp.server.messages.PeerHelloMessage;
import whatsapp.server.messages.UserLoginAnnounceMessage;
import whatsapp.server.messages.UserLogoutAnnounceMessage;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;
import whatsapp.server.node.NodeInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;

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
    private final RicartAgrawalaCoordinator ricartCoordinator;
    private final BullyElectionCoordinator bullyCoordinator;
    private final DistributedGroupManager distributedGroupManager;
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final whatsapp.server.directory.GlobalUserDirectory globalUserDirectory;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    public PeerMessageHandler(
            Socket socket,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            String selfNodeId,
            RicartAgrawalaCoordinator ricartCoordinator,
            BullyElectionCoordinator bullyCoordinator,
            DistributedGroupManager distributedGroupManager,
            LocalSessionManager<ManejadorCliente> localSessionManager,
            whatsapp.server.directory.GlobalUserDirectory globalUserDirectory,
            LamportClock lamportClock,
            EventLogger eventLogger
    ) {
        this.socket = socket;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.selfNodeId = selfNodeId;
        this.ricartCoordinator = ricartCoordinator;
        this.bullyCoordinator = bullyCoordinator;
        this.distributedGroupManager = distributedGroupManager;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);

            // CRÍTICO: OOS + flush antes de OIS para evitar deadlock de headers.
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            Object raw = in.readObject();
            if (!(raw instanceof NodeMessage)) {
                log("Mensaje inválido recibido desde " + socket.getRemoteSocketAddress());
                return;
            }

            NodeMessage message = (NodeMessage) raw;

            // Actualizar reloj de Lamport: regla de recepción max(local, L) + 1
            long updatedTs = lamportClock.update(message.getLamportTimestamp());
            eventLogger.logReceive(message.getType().name(),
                    message.getSourceNodeId() + "→" + selfNodeId, updatedTs);

            // Actualizar lastSeen en membresía
            membershipManager.markAlive(message.getSourceNodeId());
            NodeInfo senderInfo = membershipManager.getNode(message.getSourceNodeId()).orElse(null);
            if (senderInfo != null) {
                senderInfo.touch();
            }

            log("Mensaje recibido: type=" + message.getType()
                    + " source=" + message.getSourceNodeId()
                    + " L=" + message.getLamportTimestamp()
                    + " → local L=" + updatedTs);

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

                // ---- Mensajes de aplicación ----
                case APP:
                    handleAppMessage((AppMessage) message);
                    break;

                case USER_LOGIN_ANNOUNCE:
                    handleUserLoginAnnounce((UserLoginAnnounceMessage) message);
                    break;
                case USER_LOGOUT_ANNOUNCE:
                    handleUserLogoutAnnounce((UserLogoutAnnounceMessage) message);
                    break;

                default:
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
        // No se envía HEARTBEAT_ACK porque el emisor (doSend) usa "fire-and-forget" y cierra el socket inmediatamente.
        // Además, ambos nodos se envían heartbeats activamente, actualizando sus "lastSeen" sin necesidad de ACKs.
    }

    private void handleMutexRequest(MutexRequestMessage req) {
        if (ricartCoordinator != null) {
            ricartCoordinator.onMutexRequest(req.getSourceNodeId(), req.getLamportTimestamp());
        }
    }

    private void handleMutexReply(NodeMessage reply) {
        if (ricartCoordinator != null) {
            ricartCoordinator.onMutexReply(reply.getSourceNodeId());
        }
    }

    private void handleElection(ElectionMessage msg) {
        if (bullyCoordinator != null) {
            bullyCoordinator.onElectionMessage(msg.getSourceNodeId());
        }
    }

    private void handleElectionOk(NodeMessage msg) {
        if (bullyCoordinator != null) {
            bullyCoordinator.onElectionOk(msg.getSourceNodeId());
        }
    }

    private void handleElectionCoordinator(ElectionMessage msg) {
        if (bullyCoordinator != null) {
            bullyCoordinator.onCoordinatorAnnouncement(msg.getCoordinatorId());
        }
    }

    // -------------------------------------------------------------------------
    // Manejo de mensajes de aplicación (APP)
    // -------------------------------------------------------------------------

    private void handleAppMessage(AppMessage appMsg) {
        PaqueteRed payload = appMsg.getPayload();

        if (payload instanceof PaqueteMensaje) {
            PaqueteMensaje pm = (PaqueteMensaje) payload;
            String grupo = pm.getIdDestinatario();
            String remitente = pm.getIdRemitente();

            if (pm.isEsGrupo()) {
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
            log(creado
                    ? "Grupo creado remotamente: " + grupo + " por " + creador
                    : "Intento de crear grupo ya existente: " + grupo);
        }
        else if (payload instanceof PaqueteUnirseGrupo) {
            PaqueteUnirseGrupo unirse = (PaqueteUnirseGrupo) payload;
            String grupo = unirse.getIdGrupo();
            String usuario = unirse.getIdRemitente();
            boolean agregado = distributedGroupManager.addMember(grupo, usuario);
            log(agregado
                    ? "Usuario " + usuario + " se unió al grupo " + grupo + " (desde otro nodo)"
                    : "Fallo al unir usuario " + usuario + " al grupo " + grupo + " (remoto)");
        }
        else {
            log("Payload no reconocido en AppMessage: " + payload.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void handleUserLoginAnnounce(UserLoginAnnounceMessage msg) {
        globalUserDirectory.registerUserLocation(msg.getUserId(), msg.getSourceNodeId());
        log("Usuario " + msg.getUserId() + " registrado remoto en nodo " + msg.getSourceNodeId());
    }

    private void handleUserLogoutAnnounce(UserLogoutAnnounceMessage msg) {
        globalUserDirectory.removeUserLocation(msg.getUserId());
        log("Usuario " + msg.getUserId() + " desconectado remoto en nodo " + msg.getSourceNodeId());
    }

    private void log(String msg) {
        System.out.printf("[%s][PeerMessageHandler] %s%n", selfNodeId, msg);
    }
}
