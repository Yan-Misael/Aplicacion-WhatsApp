package whatsapp.server.handlers;

import whatsapp.common.models.*;
import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.messages.UserLoginAnnounceMessage;
import whatsapp.server.messages.UserLogoutAnnounceMessage;
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
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String idUsuarioAsignado;

    public ManejadorCliente(Socket socket,
                            LocalSessionManager<ManejadorCliente> localSessionManager,
                            GlobalUserDirectory globalUserDirectory,
                            DistributedGroupManager distributedGroupManager,
                            MessageRouter messageRouter,
                            PeerTransport peerTransport,
                            String localNodeId,
                            LamportClock lamportClock,
                            EventLogger eventLogger) {
        this.socket = socket;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
        this.messageRouter = messageRouter;
        this.peerTransport = peerTransport;
        this.localNodeId = localNodeId;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
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

                // 3. Tick de Lamport y broadcast USER_LOGIN_ANNOUNCE
                long ts = lamportClock.tick();
                eventLogger.logLocal("LOGIN", userId + "@" + localNodeId, ts);
                UserLoginAnnounceMessage announce = new UserLoginAnnounceMessage(localNodeId, "*", userId, ts);
                peerTransport.broadcast(announce);

                enviarObjeto(new PaqueteConfirm(userId, true, "Login con éxito"));
                System.out.println("Usuario " + userId + " autenticado en " + localNodeId + " L=" + ts);
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
            localSessionManager.removeLocalSession(idUsuarioAsignado);
            globalUserDirectory.removeUserLocation(idUsuarioAsignado);
            long ts = lamportClock.tick();
            eventLogger.logLocal("LOGOUT", idUsuarioAsignado + "@" + localNodeId, ts);
            UserLogoutAnnounceMessage announce = new UserLogoutAnnounceMessage(localNodeId, "*", idUsuarioAsignado, ts);
            peerTransport.broadcast(announce);
        }
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}
