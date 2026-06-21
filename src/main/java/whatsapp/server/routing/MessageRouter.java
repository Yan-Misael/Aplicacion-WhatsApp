package whatsapp.server.routing;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import whatsapp.common.models.PaqueteConfirm;
import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteError;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.common.models.PaqueteUnirseGrupo;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.AppMessage;
import whatsapp.server.peer.PeerTransport;

public class MessageRouter {
    private final String selfNodeId;
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;
    private final MembershipManager membershipManager;
    private final PeerTransport peerTransport;

    public MessageRouter(String selfNodeId,
                         LocalSessionManager<ManejadorCliente> localSessionManager,
                         GlobalUserDirectory globalUserDirectory,
                         DistributedGroupManager distributedGroupManager,
                         MembershipManager membershipManager,
                         PeerTransport peerTransport) {
        this.selfNodeId = selfNodeId;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
    }

    // ---------- Mensaje privado ----------
    public void routePrivateMessage(PaqueteMensaje msg, ManejadorCliente remitente) throws IOException {
        String destino = msg.getIdDestinatario();

        // 1. ¿Está localmente?
        Optional<ManejadorCliente> localDest = localSessionManager.getLocalSession(destino);
        if (localDest.isPresent()) {
            localDest.get().enviarObjeto(msg);
            System.out.println("[Router] Mensaje privado entregado localmente a " + destino);
            return;
        }

        // 2. Consultar directorio global
        Optional<String> nodeId = globalUserDirectory.findNodeForUser(destino);
        if (nodeId.isEmpty()) {
            remitente.enviarObjeto(new PaqueteError("Servidor", "Usuario '" + destino + "' no conectado."));
            return;
        }

        // 3. Reenviar a otro nodo
        AppMessage appMsg = new AppMessage(selfNodeId, nodeId.get(), msg, 0L); // Lamport será completado por Persona 4
        peerTransport.sendToNode(nodeId.get(), appMsg);
        System.out.println("[Router] Mensaje privado reenviado a nodo " + nodeId.get() + " para " + destino);
    }

    // ---------- Mensaje grupal ----------
    public void routeGroupMessage(PaqueteMensaje msg, ManejadorCliente remitente) throws IOException {
        String grupo = msg.getIdDestinatario();
        String remitenteId = msg.getIdRemitente();

        // Validar grupo y membresía (local)
        if (!distributedGroupManager.groupExists(grupo)) {
            remitente.enviarObjeto(new PaqueteError("Servidor", "El grupo '" + grupo + "' no existe."));
            return;
        }
        if (!distributedGroupManager.isMember(grupo, remitenteId)) {
            remitente.enviarObjeto(new PaqueteError("Servidor", "No eres miembro del grupo '" + grupo + "'."));
            return;
        }

        // 1. Entregar a miembros locales (excepto emisor)
        Set<String> miembrosLocales = distributedGroupManager.getMembersSnapshot(grupo);
        for (String miembro : miembrosLocales) {
            if (miembro.equals(remitenteId)) continue;
            Optional<ManejadorCliente> cli = localSessionManager.getLocalSession(miembro);
            if (cli.isPresent()) {
                cli.get().enviarObjeto(msg);
                System.out.println("[Router] Mensaje grupal entregado localmente a " + miembro);
            }
        }

        // 2. Reenviar a otros nodos (broadcast)
        // Nota: si quieres optimizar, puedes mantener un registro de nodos con miembros.
        // Por simplicidad, broadcast a todos los nodos vivos.
        AppMessage appMsg = new AppMessage(selfNodeId, "*", msg, 0L);
        peerTransport.broadcast(appMsg);
        System.out.println("[Router] Mensaje grupal difundido a todos los nodos.");
    }

    // ---------- Creación de grupo ----------
    public void routeCreateGroup(String grupo, String creador, ManejadorCliente remitente) throws IOException {
        // Crear localmente
        boolean creado = distributedGroupManager.createGroup(grupo, Set.of(creador));
        if (!creado) {
            remitente.enviarObjeto(new PaqueteError("Servidor", "El grupo '" + grupo + "' ya existe."));
            return;
        }

        // Propagar a otros nodos
        PaqueteCrearGrupo payload = new PaqueteCrearGrupo(creador, grupo);
        AppMessage appMsg = new AppMessage(selfNodeId, "*", payload, 0L);
        peerTransport.broadcast(appMsg);

        remitente.enviarObjeto(new PaqueteConfirm(creador, true, "Grupo '" + grupo + "' creado."));
        System.out.println("[Router] Grupo " + grupo + " creado y difundido.");
    }

    // ---------- Unión a grupo ----------
    public void routeJoinGroup(String grupo, String usuario, ManejadorCliente remitente) throws IOException {
        // Unirse localmente
        boolean agregado = distributedGroupManager.addMember(grupo, usuario);
        if (!agregado) {
            remitente.enviarObjeto(new PaqueteError("Servidor", "No se pudo unir al grupo '" + grupo + "'."));
            return;
        }

        // Propagar a otros nodos
        PaqueteUnirseGrupo payload = new PaqueteUnirseGrupo(usuario, grupo);
        AppMessage appMsg = new AppMessage(selfNodeId, "*", payload, 0L);
        peerTransport.broadcast(appMsg);

        remitente.enviarObjeto(new PaqueteConfirm(usuario, true, "Te uniste al grupo '" + grupo + "'."));
        System.out.println("[Router] Usuario " + usuario + " se unió a " + grupo + " y se difundió.");
    }
}