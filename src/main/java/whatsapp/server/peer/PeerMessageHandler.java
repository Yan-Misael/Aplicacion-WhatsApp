package whatsapp.server.peer;

import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.common.models.PaqueteRed;
import whatsapp.common.models.PaqueteUnirseGrupo;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.AppMessage;
import whatsapp.server.messages.HeartbeatMessage;
import whatsapp.server.messages.MembershipUpdateMessage;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.messages.PeerHelloAckMessage;
import whatsapp.server.messages.PeerHelloMessage;
import whatsapp.server.node.NodeInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;

public class PeerMessageHandler implements Runnable {

    private final Socket socket;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;
    private final String selfNodeId;

    // Nuevos campos para manejar mensajes de aplicación
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;

    public PeerMessageHandler(
            Socket socket,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            String selfNodeId,
            LocalSessionManager<ManejadorCliente> localSessionManager,
            GlobalUserDirectory globalUserDirectory,
            DistributedGroupManager distributedGroupManager
    ) {
        this.socket = socket;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.selfNodeId = selfNodeId;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);

            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            Object raw = in.readObject();
            if (!(raw instanceof NodeMessage)) {
                log("Mensaje inválido recibido desde " + socket.getRemoteSocketAddress());
                return;
            }

            NodeMessage message = (NodeMessage) raw;
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
                // ---- NUEVO: manejar mensajes de aplicación ----
                case APP:
                    AppMessage appMsg = (AppMessage) message;
                    handleAppMessage(appMsg);
                    break;
                default:
                    log("Tipo pendiente: " + message.getType() + " desde " + message.getSourceNodeId());
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
    // Manejadores existentes (ya los tienes, los dejo igual)
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

        PeerHelloAckMessage ack = new PeerHelloAckMessage(
                selfNodeId,
                sourceId,
                0L,
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
        // Enviar ACK
        out.writeObject(new NodeMessage(selfNodeId, hb.getSourceNodeId(), NodeMessageType.HEARTBEAT_ACK, 0L));
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