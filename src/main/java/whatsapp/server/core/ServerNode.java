package whatsapp.server.core;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.config.NodeConfig;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.peer.HeartbeatEmitterTask;
import whatsapp.server.peer.HeartbeatSweeperTask;
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
 * <p>Esta versión implementa el punto 2.3 de coordinación distribuida:</p>
 * <ul>
 *   <li><b>Ricart-Agrawala</b>: exclusión mutua sobre GROUP_REGISTRY.</li>
 *   <li><b>Bully</b>: elección de coordinador al detectar caída.</li>
 * </ul>
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
    private final LocalSessionManager<ManejadorCliente> localSessionManager;

    private final TcpPeerTransport peerTransport;
    private final MessageRouter messageRouter;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    private final RicartAgrawalaCoordinator ricartCoordinator;
    private final BullyElectionCoordinator bullyCoordinator;

    private final ServerNodeContext context;

    public ServerNode(NodeConfig config) {
        this.config = config;
        this.selfInfo = config.toNodeInfo();

        this.clientWorkerPool  = Executors.newFixedThreadPool(config.getClientPoolSize());
        this.peerWorkerPool    = Executors.newFixedThreadPool(config.getPeerPoolSize());
        this.schedulerPool     = Executors.newScheduledThreadPool(config.getSchedulerPoolSize());
        this.coordinationExecutor = Executors.newSingleThreadExecutor();

        this.membershipManager     = new MembershipManager(selfInfo, config.getPeers());
        this.globalUserDirectory   = new GlobalUserDirectory();
        this.distributedGroupManager = new DistributedGroupManager();
        this.localSessionManager   = new LocalSessionManager<ManejadorCliente>();

        this.lamportClock = new LamportClock();
        this.eventLogger  = new EventLogger(config.getNodeId());

        // Transporte TCP (sin coordinadores aún — se inyectan tras crearlos)
        this.peerTransport = new TcpPeerTransport(
                config.getNodeId(),
                config.getPeerPort(),
                peerWorkerPool,
                membershipManager,
                config.getPeerSocketTimeoutMs(),
                lamportClock,
                eventLogger,
                distributedGroupManager,
                localSessionManager,
                globalUserDirectory
        );

        this.messageRouter = new MessageRouter(
                config.getNodeId(),
                localSessionManager,
                globalUserDirectory,
                distributedGroupManager,
                membershipManager,
                peerTransport,
                lamportClock,
                eventLogger
        );

        // --- Coordinación distribuida (Punto 2.3) ---

        // Ricart-Agrawala: exclusión mutua sobre GROUP_REGISTRY
        this.ricartCoordinator = new RicartAgrawalaCoordinator(
                config.getNodeId(),
                membershipManager,
                peerTransport,
                lamportClock,
                eventLogger
        );

        // Bully: elección de coordinador
        this.bullyCoordinator = new BullyElectionCoordinator(
                config.getNodeId(),
                membershipManager,
                peerTransport,
                lamportClock,
                eventLogger,
                schedulerPool
        );

        // Inyectar coordinadores en el listener ANTES de llamar a start()
        peerTransport.setCoordinators(ricartCoordinator, bullyCoordinator);

        // Inyectar R-A en DistributedGroupManager para proteger operaciones de escritura
        distributedGroupManager.setRicartCoordinator(ricartCoordinator);

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
                messageRouter,
                lamportClock,
                eventLogger,
                ricartCoordinator,
                bullyCoordinator
        );
    }

    /**
     * Inicia el nodo: levanta el PeerListener, conecta con peers y arranca heartbeats.
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

        // Los coordinadores ya están inyectados — ahora sí arrancamos el transporte
        peerTransport.start();

        long heartbeatInterval = config.getHeartbeatIntervalMs();
        long heartbeatTimeout  = config.getHeartbeatTimeoutMs();

        HeartbeatEmitterTask emitterTask = new HeartbeatEmitterTask(
                config.getNodeId(),
                membershipManager,
                peerTransport,
                lamportClock,
                eventLogger
        );

        schedulerPool.scheduleAtFixedRate(
                emitterTask,
                heartbeatInterval,
                heartbeatInterval,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        log("Emisor de Heartbeats programado cada " + heartbeatInterval + "ms");

        // Sweeper ahora notifica a ambos coordinadores al detectar fallos
        HeartbeatSweeperTask sweeperTask = new HeartbeatSweeperTask(
                config.getNodeId(),
                membershipManager,
                config.getHeartbeatTimeoutMs(),
                globalUserDirectory,
                ricartCoordinator,
                bullyCoordinator
        );

        schedulerPool.scheduleAtFixedRate(
                sweeperTask,
                heartbeatTimeout,
                heartbeatInterval,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        log("Sweeper de fallos programado. Tolerancia máxima: " + heartbeatTimeout + "ms");

        // Demo de Ricart-Agrawala: todos los nodos intentan crear el mismo grupo
        // simultáneamente para demostrar exclusión mutua distribuida.
        schedulerPool.schedule(
                () -> {
                    String grupoDemo = "grupo-demo-ricart";
                    log("[DEMO R-A] Intentando crear grupo '" + grupoDemo + "' con mutex distribuido...");
                    boolean creado = distributedGroupManager.createGroup(
                            grupoDemo, java.util.Set.of(config.getNodeId()));
                    if (creado) {
                        log("[DEMO R-A] Grupo '" + grupoDemo + "' CREADO exitosamente por " + config.getNodeId());
                    } else {
                        log("[DEMO R-A] Grupo '" + grupoDemo + "' ya existe — otro nodo llegó primero.");
                    }
                },
                heartbeatInterval * 3,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );

        // Elección inicial: si nadie conoce al coordinador aún, disparar Bully
        // tras un retardo para dar tiempo a que los PEER_HELLO se completen.
        schedulerPool.schedule(
                () -> {
                    if (bullyCoordinator.getCurrentCoordinator() == null) {
                        log("Sin coordinador conocido — iniciando elección Bully inicial");
                        bullyCoordinator.startElection();
                    }
                },
                heartbeatInterval * 2,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );

        log("ServerNode iniciado — Ricart-Agrawala y Bully activos.");

        startClientListener();
    }

    private java.net.ServerSocket clientServerSocket;
    private volatile boolean runningClientListener = false;

    private void startClientListener() {
        runningClientListener = true;
        clientWorkerPool.submit(() -> {
            try {
                clientServerSocket = new java.net.ServerSocket(config.getClientPort());
                log("ClientListener escuchando en puerto " + config.getClientPort());
                while (runningClientListener) {
                    java.net.Socket socketCliente = clientServerSocket.accept();
                    log("Nueva conexión de cliente desde: " + socketCliente.getInetAddress());
                    ManejadorCliente manejador = new ManejadorCliente(
                            socketCliente,
                            localSessionManager,
                            globalUserDirectory,
                            distributedGroupManager,
                            messageRouter,
                            peerTransport,
                            config.getNodeId(),
                            lamportClock,
                            eventLogger
                    );
                    manejador.start();
                }
            } catch (IOException e) {
                if (runningClientListener) {
                    log("Error en ClientListener: " + e.getMessage());
                }
            }
        });
    }

    private void stopClientListener() {
        runningClientListener = false;
        try {
            if (clientServerSocket != null) {
                clientServerSocket.close();
            }
        } catch (IOException e) {
            log("Error cerrando ClientListener: " + e.getMessage());
        }
    }

    /**
     * Detiene el nodo y libera recursos.
     */
    public void stop() {
        log("Deteniendo ServerNode");

        stopClientListener();

        peerTransport.stop();

        eventLogger.printSortedByLamport();
        eventLogger.flushToFile(java.nio.file.Path.of("logs"));

        clientWorkerPool.shutdownNow();
        peerWorkerPool.shutdownNow();
        schedulerPool.shutdownNow();
        coordinationExecutor.shutdownNow();

        log("ServerNode detenido");
    }

    public ServerNodeContext getContext() {
        return context;
    }

    private void log(String message) {
        System.out.printf("[%s] %s%n", config.getNodeId(), message);
    }

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
