package whatsapp.server.handlers;

import whatsapp.common.models.*;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.peer.PeerTransport;
import whatsapp.server.routing.MessageRouter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Set;

public class ManejadorCliente extends Thread {
    private final Socket socket;
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;
    private final MessageRouter messageRouter;
    private final PeerTransport peerTransport;
    private final String localNodeId;

    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String idUsuarioAsignado;

    public ManejadorCliente(Socket socket,
                            LocalSessionManager<ManejadorCliente> localSessionManager,
                            GlobalUserDirectory globalUserDirectory,
                            DistributedGroupManager distributedGroupManager,
                            MessageRouter messageRouter,
                            PeerTransport peerTransport,
                            String localNodeId) {
        this.socket = socket;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
        this.messageRouter = messageRouter;
        this.peerTransport = peerTransport;
        this.localNodeId = localNodeId;
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            while (!socket.isClosed()) {
                Object peticion = in.readObject();
                if (peticion instanceof PaqueteRed) {
                    procesarPaquete((PaqueteRed) peticion);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("[Alerta] Cliente desconectado abruptamente: " + socket.getInetAddress());
        } finally {
            liberarRecursos();
        }
    }

    private void procesarPaquete(PaqueteRed paquete) throws IOException {
        if (paquete instanceof PaqueteLogin) {
            PaqueteLogin login = (PaqueteLogin) paquete;
            String userId = login.getIdRemitente();

            // 1. Registrar localmente
            boolean registrado = localSessionManager.registerLocalSession(userId, this);
            if (registrado) {
                this.idUsuarioAsignado = userId;
                // 2. Anunciar al directorio global
                globalUserDirectory.registerUserLocation(userId, localNodeId);
                // 3. Notificar a otros nodos (opcional pero recomendado)
                NodeMessage announce = new NodeMessage(localNodeId, "*", NodeMessageType.USER_LOGIN_ANNOUNCE, 0L);
                peerTransport.broadcast(announce);

                enviarObjeto(new PaqueteConfirm(userId, true, "Login con éxito"));
                System.out.println("Usuario " + userId + " autenticado en " + localNodeId);
            } else {
                enviarObjeto(new PaqueteError(userId, "ID ya en uso en este nodo."));
                liberarRecursos();
            }
        }
        else if (paquete instanceof PaqueteMensaje) {
            if (idUsuarioAsignado == null) {
                enviarObjeto(new PaqueteError("Servidor", "Debe autenticarse."));
                return;
            }
            PaqueteMensaje msg = (PaqueteMensaje) paquete;
            if (msg.isEsGrupo()) {
                messageRouter.routeGroupMessage(msg, this);
            } else {
                messageRouter.routePrivateMessage(msg, this);
            }
        }
        else if (paquete instanceof PaqueteCrearGrupo) {
            if (idUsuarioAsignado == null) {
                enviarObjeto(new PaqueteError("Servidor", "Debe autenticarse."));
                return;
            }
            PaqueteCrearGrupo crear = (PaqueteCrearGrupo) paquete;
            messageRouter.routeCreateGroup(crear.getIdGrupo(), idUsuarioAsignado, this);
        }
        else if (paquete instanceof PaqueteUnirseGrupo) {
            if (idUsuarioAsignado == null) {
                enviarObjeto(new PaqueteError("Servidor", "Debe autenticarse."));
                return;
            }
            PaqueteUnirseGrupo unirse = (PaqueteUnirseGrupo) paquete;
            messageRouter.routeJoinGroup(unirse.getIdGrupo(), idUsuarioAsignado, this);
        }
        else if (paquete instanceof PaqueteLogout) {
            liberarRecursos();
        }
        else {
            enviarObjeto(new PaqueteError(
                idUsuarioAsignado != null ? idUsuarioAsignado : "Servidor",
                "Paquete no soportado."
            ));
        }
    }

    public void enviarObjeto(PaqueteRed paquete) throws IOException {
        synchronized (out) {
            out.writeObject(paquete);
            out.flush();
            out.reset();
        }
    }

    private void liberarRecursos() {
        if (idUsuarioAsignado != null) {
            // Eliminar sesión local
            localSessionManager.removeLocalSession(idUsuarioAsignado);
            // Eliminar del directorio global
            globalUserDirectory.removeUserLocation(idUsuarioAsignado);
            // Remover de todos los grupos locales (opcional)
            // Nota: si no tienes un método para obtener todos los grupos,
            // puedes omitir esta parte; el sweeper limpiará cuando el nodo caiga.
            // Por seguridad, si tienes un método getAllGroupIds(), úsalo.
            // Ejemplo:
            // for (String grupo : distributedGroupManager.getAllGroupIds()) {
            //     distributedGroupManager.removeMember(grupo, idUsuarioAsignado);
            // }
        }
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}