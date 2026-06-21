package whatsapp.server.core;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.peer.NoOpPeerTransport;
import whatsapp.server.routing.MessageRouter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * Este código establece un servidor multi-hilo (un hilo por cliente) y prepara
 * el terreno para la sincronización de recursos y el manejo de fallos independientes.
 * Además, por diseño, es en el servidor el único lugar donde se instancian los gestores de estado;
 * se crean aquí y se inyectan vía DI en cada ManejadorCliente. También se configura la topología de la red.
*/
public class ServidorPrincipal {
    private static final int PUERTO = 2346;
    private static final String NODE_ID = "local";

    public static void main(String[] args) {
        System.out.println("=== Iniciando Nodo Servidor de WhatsApp ===");

        LocalSessionManager<ManejadorCliente> sessionManager = new LocalSessionManager<>();
        DistributedGroupManager groupManager = new DistributedGroupManager();
        GlobalUserDirectory globalUserDirectory = new GlobalUserDirectory();
        NoOpPeerTransport peerTransport = new NoOpPeerTransport(NODE_ID);
        LamportClock lamportClock = new LamportClock();
        EventLogger eventLogger = new EventLogger(NODE_ID);

        NodeInfo selfInfo = new NodeInfo(NODE_ID, "localhost", PUERTO, PUERTO + 1000);
        MembershipManager membershipManager = new MembershipManager(selfInfo, Collections.emptyList());

        MessageRouter messageRouter = new MessageRouter(
                NODE_ID,
                sessionManager,
                globalUserDirectory,
                groupManager,
                membershipManager,
                peerTransport,
                lamportClock,
                eventLogger
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            eventLogger.printSortedByLamport();
            eventLogger.flushToFile(java.nio.file.Path.of("logs"));
        }));

        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("Servidor escuchando en el puerto " + PUERTO + "...\n");

            while (true) {
                Socket socketCliente = serverSocket.accept();
                System.out.println("[Servidor] Nueva conexión establecida desde: " + socketCliente.getInetAddress());

                ManejadorCliente manejador = new ManejadorCliente(
                        socketCliente,
                        sessionManager,
                        globalUserDirectory,
                        groupManager,
                        messageRouter,
                        peerTransport,
                        NODE_ID,
                        lamportClock,
                        eventLogger
                );
                manejador.start();
            }
        } catch (IOException e) {
            System.err.println("[Error Critico] Fallo en el socket principal: " + e.getMessage());
        }
    }
}
