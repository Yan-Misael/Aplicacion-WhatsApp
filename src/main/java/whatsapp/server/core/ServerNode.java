package whatsapp.server.core;

import whatsapp.server.config.NodeConfig;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.peer.HeartbeatEmitterTask;
import whatsapp.server.peer.HeartbeatSweeperTask;
import whatsapp.server.peer.PeerTransport;
import whatsapp.server.peer.TcpPeerTransport;
import whatsapp.server.routing.MessageRouter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Nodo servidor principal de la arquitectura multiservidor.
 *
 * <p>Cada instancia de {@code ServerNode} representa un proceso/JVM independiente.
 * Un nodo cumple dos roles:</p>
 *
 * <ol>
 *     <li>Servidor de clientes locales.</li>
 *     <li>Peer distribuido que se comunica con otros nodos servidores.</li>
 * </ol>
 *
 * <p>Esta versión corresponde a la implementación de Persona 2. Incluye comunicación
 * TCP real entre nodos mediante {@link whatsapp.server.peer.TcpPeerTransport}.</p>
 */
public class ServerNode {

    private final NodeConfig config;
    private final NodeInfo selfInfo;

    private final ExecutorService clientWorkerPool;
    private final ExecutorService peerWorkerPool;
    private final ScheduledExecutorService schedulerPool;
    private final ExecutorService coordinationExecutor;

    private final MembershipManager membershipManager;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;
    private final LocalSessionManager<Object> localSessionManager;

    private final PeerTransport peerTransport;
    private final MessageRouter messageRouter;

    private final ServerNodeContext context;

    /**
     * Construye un nodo servidor a partir de una configuración.
     *
     * @param config configuración del nodo
     */
    public ServerNode(NodeConfig config) {
        this.config = config;
        this.selfInfo = config.toNodeInfo();

        this.clientWorkerPool = Executors.newFixedThreadPool(config.getClientPoolSize());
        this.peerWorkerPool = Executors.newFixedThreadPool(config.getPeerPoolSize());
        this.schedulerPool = Executors.newScheduledThreadPool(config.getSchedulerPoolSize());
        this.coordinationExecutor = Executors.newSingleThreadExecutor();

        this.membershipManager = new MembershipManager(selfInfo, config.getPeers());
        this.globalUserDirectory = new GlobalUserDirectory();
        this.distributedGroupManager = new DistributedGroupManager();
        this.localSessionManager = new LocalSessionManager<>();

        /*
         * Implementación TCP real — Persona 2.
         *
         * Reemplaza NoOpPeerTransport con una implementación basada en sockets TCP.
         */
        this.peerTransport = new TcpPeerTransport(
                config.getNodeId(),
                config.getPeerPort(),
                peerWorkerPool,
                membershipManager,
                config.getPeerSocketTimeoutMs()
        );

        this.messageRouter = new MessageRouter(
                config.getNodeId(),
                globalUserDirectory,
                distributedGroupManager,
                membershipManager,
                peerTransport
        );

        this.context = new ServerNodeContext(
                config,
                clientWorkerPool,
                peerWorkerPool,
                schedulerPool,
                coordinationExecutor,
                membershipManager,
                globalUserDirectory,
                distributedGroupManager,
                localSessionManager,
                peerTransport,
                messageRouter
        );
    }

    /**
     * Inicia el nodo servidor.
     *
     * <p>Levanta el {@link whatsapp.server.peer.PeerListener} inter-nodo y envía
     * {@code PEER_HELLO} a los peers configurados.</p>
     */
    public void start() {
        log("Iniciando ServerNode");
        log("clientPort=" + config.getClientPort() + " peerPort=" + config.getPeerPort());
        log("clientWorkerPool=" + config.getClientPoolSize()
                + " peerWorkerPool=" + config.getPeerPoolSize()
                + " schedulerPool=" + config.getSchedulerPoolSize()
                + " coordinationExecutor=" + config.getCoordinationPoolSize());

        log("Peers configurados:");
        for (NodeInfo peer : config.getPeers()) {
            log(" - " + peer);
        }

        peerTransport.start();
        
        // --- Tolerancia a Fallos ---
        long heartbeatInterval = config.getHeartbeatIntervalMs();
        long heartbeatTimeout = config.getHeartbeatTimeoutMs();

        // Tarea que envía los latidos
        HeartbeatEmitterTask emitterTask = new HeartbeatEmitterTask(
                config.getNodeId(),
                membershipManager,
                peerTransport
        );

        schedulerPool.scheduleAtFixedRate(
                emitterTask,
                heartbeatInterval, 
                heartbeatInterval, 
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        log("Emisor de Heartbeats programado cada " + heartbeatInterval + "ms");

        // Tarea que recolecta los fallos (Sweeper)
        HeartbeatSweeperTask sweeperTask = new HeartbeatSweeperTask(
                config.getNodeId(),
                membershipManager,
                heartbeatTimeout
        );

        // Se ejecuta un poco más seguido que el timeout para detectar la caída lo más pronto posible
        schedulerPool.scheduleAtFixedRate(
                sweeperTask,
                heartbeatTimeout, // Retardo inicial para dar tiempo al arranque
                heartbeatInterval, // Usa el mismo ritmo del intervalo base para chequear
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        log("Sweeper de fallos programado. Tolerancia máxima de inactividad: " + heartbeatTimeout + "ms");
        
        log("ServerNode iniciado con comunicación TCP real entre nodos.");
    }

    /**
     * Detiene el nodo y libera recursos.
     *
     * <p>Se cierran los pools para evitar hilos vivos después de terminar el nodo.</p>
     */
    public void stop() {
        log("Deteniendo ServerNode");

        peerTransport.stop();

        clientWorkerPool.shutdownNow();
        peerWorkerPool.shutdownNow();
        schedulerPool.shutdownNow();
        coordinationExecutor.shutdownNow();

        log("ServerNode detenido");
    }

    /**
     * @return contexto interno del nodo
     */
    public ServerNodeContext getContext() {
        return context;
    }

    /**
     * Imprime un mensaje de log con prefijo del nodo.
     *
     * @param message mensaje a registrar
     */
    private void log(String message) {
        System.out.printf("[%s] %s%n", config.getNodeId(), message);
    }

    /**
     * Punto de entrada para ejecutar un {@code ServerNode}.
     *
     * @param args argumentos de línea de comandos.
     *             Se espera un argumento: ruta al archivo de configuración.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java whatsapp.server.ServerNode <config.properties>");
            System.err.println("Ejemplo: java whatsapp.server.ServerNode config/node1.properties");
            return;
        }

        try {
            NodeConfig config = NodeConfig.fromFile(args[0]);
            ServerNode node = new ServerNode(config);

            Runtime.getRuntime().addShutdownHook(new Thread(node::stop));

            node.start();

        } catch (IOException e) {
            System.err.println("No se pudo cargar configuración: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error iniciando ServerNode: " + e.getMessage());
            e.printStackTrace();
        }
    }
}