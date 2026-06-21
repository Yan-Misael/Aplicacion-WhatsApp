package whatsapp.server.core;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.config.NodeConfig;
import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;
import whatsapp.server.peer.PeerTransport;
import whatsapp.server.routing.MessageRouter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Contenedor de dependencias principales de un {@link ServerNode}.
 *
 * <p>Esta clase facilita compartir componentes internos del nodo sin pasar una
 * cantidad excesiva de parámetros entre constructores.</p>
 */
public class ServerNodeContext {

    private final NodeConfig config;
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
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    private final RicartAgrawalaCoordinator ricartCoordinator;
    private final BullyElectionCoordinator bullyCoordinator;

    public ServerNodeContext(
            NodeConfig config,
            ExecutorService clientWorkerPool,
            ExecutorService peerWorkerPool,
            ScheduledExecutorService schedulerPool,
            ExecutorService coordinationExecutor,
            MembershipManager membershipManager,
            GlobalUserDirectory globalUserDirectory,
            DistributedGroupManager distributedGroupManager,
            LocalSessionManager<Object> localSessionManager,
            PeerTransport peerTransport,
            MessageRouter messageRouter,
            LamportClock lamportClock,
            EventLogger eventLogger,
            RicartAgrawalaCoordinator ricartCoordinator,
            BullyElectionCoordinator bullyCoordinator
    ) {
        this.config = config;
        this.clientWorkerPool = clientWorkerPool;
        this.peerWorkerPool = peerWorkerPool;
        this.schedulerPool = schedulerPool;
        this.coordinationExecutor = coordinationExecutor;
        this.membershipManager = membershipManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
        this.localSessionManager = localSessionManager;
        this.peerTransport = peerTransport;
        this.messageRouter = messageRouter;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
        this.ricartCoordinator = ricartCoordinator;
        this.bullyCoordinator = bullyCoordinator;
    }

    public NodeConfig getConfig() { return config; }
    public ExecutorService getClientWorkerPool() { return clientWorkerPool; }
    public ExecutorService getPeerWorkerPool() { return peerWorkerPool; }
    public ScheduledExecutorService getSchedulerPool() { return schedulerPool; }
    public ExecutorService getCoordinationExecutor() { return coordinationExecutor; }
    public MembershipManager getMembershipManager() { return membershipManager; }
    public GlobalUserDirectory getGlobalUserDirectory() { return globalUserDirectory; }
    public DistributedGroupManager getDistributedGroupManager() { return distributedGroupManager; }
    public LocalSessionManager<Object> getLocalSessionManager() { return localSessionManager; }
    public PeerTransport getPeerTransport() { return peerTransport; }
    public MessageRouter getMessageRouter() { return messageRouter; }
    public LamportClock getLamportClock() { return lamportClock; }
    public EventLogger getEventLogger() { return eventLogger; }
    public RicartAgrawalaCoordinator getRicartCoordinator() { return ricartCoordinator; }
    public BullyElectionCoordinator getBullyCoordinator() { return bullyCoordinator; }
}
