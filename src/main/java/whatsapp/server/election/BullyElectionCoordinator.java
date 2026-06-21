package whatsapp.server.election;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.ElectionMessage;
import whatsapp.server.messages.NodeMessageType;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.peer.PeerTransport;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementa el algoritmo de elección de coordinador Bully.
 *
 * <p>El nodo con mayor {@code nodeId} lexicográfico entre los nodos ALIVE es
 * el coordinador. La elección se inicia automáticamente cuando se detecta que
 * el coordinador actual cayó (llamada desde {@code HeartbeatSweeperTask}).</p>
 *
 * <h2>Flujo del algoritmo</h2>
 * <ol>
 *   <li>Al detectar caída del coordinador: {@link #startElection()}.</li>
 *   <li>Se envía {@code ELECTION} a todos los nodos ALIVE con ID mayor.</li>
 *   <li>Si alguno responde {@code ELECTION_OK}: se espera el anuncio final.</li>
 *   <li>Si nadie responde en {@value #ELECTION_TIMEOUT_MS} ms: se auto-proclama
 *       coordinador y difunde {@code ELECTION_COORDINATOR}.</li>
 *   <li>Al recibir {@code ELECTION_COORDINATOR}: actualiza el coordinador conocido.</li>
 * </ol>
 */
public class BullyElectionCoordinator {

    /** Tiempo de espera máximo para recibir ELECTION_OK tras enviar ELECTION. */
    private static final long ELECTION_TIMEOUT_MS = 5_000;

    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final PeerTransport peerTransport;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;
    private final ScheduledExecutorService schedulerPool;

    private volatile String currentCoordinatorId = null;
    private boolean electionInProgress = false;
    private ScheduledFuture<?> timeoutFuture = null;

    public BullyElectionCoordinator(
            String selfNodeId,
            MembershipManager membershipManager,
            PeerTransport peerTransport,
            LamportClock lamportClock,
            EventLogger eventLogger,
            ScheduledExecutorService schedulerPool) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
        this.schedulerPool = schedulerPool;
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /**
     * Inicia una elección. Idempotente: si ya hay una en curso, no hace nada.
     * Llamado por {@code HeartbeatSweeperTask} cuando el coordinador cae.
     */
    public synchronized void startElection() {
        if (electionInProgress) {
            log("Elección ya en progreso — ignorando nueva solicitud");
            return;
        }

        electionInProgress = true;
        log("=== INICIANDO ELECCIÓN ===");

        List<NodeInfo> highers = getHigherIdAliveNodes();

        if (highers.isEmpty()) {
            // Soy el de mayor ID entre los ALIVE → me proclamo coordinador
            proclaim();
            return;
        }

        // Enviar ELECTION a todos los nodos ALIVE con ID mayor
        for (NodeInfo node : highers) {
            long ts = lamportClock.tick();
            ElectionMessage msg = new ElectionMessage(
                    selfNodeId, node.getNodeId(), NodeMessageType.ELECTION, ts);
            peerTransport.sendToNode(node.getNodeId(), msg);
            eventLogger.logSend("ELECTION", selfNodeId + "→" + node.getNodeId(), ts);
            log("ELECTION → " + node.getNodeId());
        }

        // Programar timeout: si no recibimos ELECTION_OK en ELECTION_TIMEOUT_MS, nos proclamamos
        timeoutFuture = schedulerPool.schedule(
                this::onElectionTimeout,
                ELECTION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Procesa un mensaje {@code ELECTION} recibido de un nodo con ID menor.
     * Respondemos con {@code ELECTION_OK} e iniciamos nuestra propia elección.
     *
     * @param senderId nodo que inició la elección
     */
    public synchronized void onElectionMessage(String senderId) {
        log("ELECTION recibido de " + senderId + " (tenemos ID mayor)");

        // Responder ELECTION_OK al candidato inferior
        long ts = lamportClock.tick();
        ElectionMessage ok = new ElectionMessage(
                selfNodeId, senderId, NodeMessageType.ELECTION_OK, ts);
        peerTransport.sendToNode(senderId, ok);
        eventLogger.logSend("ELECTION_OK", selfNodeId + "→" + senderId, ts);

        // Iniciar nuestra propia elección si no hay una en curso
        if (!electionInProgress) {
            startElection();
        }
    }

    /**
     * Procesa un mensaje {@code ELECTION_OK}: alguien con mayor ID está vivo.
     * Cancelamos el timer de auto-proclamación y esperamos el ELECTION_COORDINATOR.
     *
     * @param senderId nodo que respondió
     */
    public synchronized void onElectionOk(String senderId) {
        log("ELECTION_OK recibido de " + senderId + " — esperando COORDINATOR");
        cancelTimeout();
        // No nos proclamamos; esperamos el mensaje ELECTION_COORDINATOR del ganador
    }

    /**
     * Procesa un mensaje {@code ELECTION_COORDINATOR}: hay un nuevo coordinador.
     *
     * @param coordinatorId ID del nuevo coordinador
     */
    public synchronized void onCoordinatorAnnouncement(String coordinatorId) {
        currentCoordinatorId = coordinatorId;
        electionInProgress = false;
        cancelTimeout();
        log("=== COORDINADOR ELECTO: " + coordinatorId + " ===");
    }

    /** @return ID del coordinador actual, o {@code null} si no se conoce */
    public String getCurrentCoordinator() {
        return currentCoordinatorId;
    }

    /** @return {@code true} si este nodo es el coordinador actual */
    public boolean isSelfCoordinator() {
        return selfNodeId.equals(currentCoordinatorId);
    }

    // -------------------------------------------------------------------------
    // Métodos internos
    // -------------------------------------------------------------------------

    private void onElectionTimeout() {
        synchronized (this) {
            if (!electionInProgress) {
                return;
            }
            log("Timeout de elección sin ELECTION_OK — me proclamo coordinador");
            proclaim();
        }
    }

    /** Se declara coordinador y difunde el anuncio a todos los peers ALIVE. */
    private void proclaim() {
        currentCoordinatorId = selfNodeId;
        electionInProgress = false;
        cancelTimeout();

        log("=== ME PROCLAMO COORDINADOR: " + selfNodeId + " ===");

        for (NodeInfo peer : membershipManager.getAliveNodes()) {
            long ts = lamportClock.tick();
            ElectionMessage announce = new ElectionMessage(
                    selfNodeId, peer.getNodeId(), ts, selfNodeId);
            peerTransport.sendToNode(peer.getNodeId(), announce);
            eventLogger.logSend("ELECTION_COORDINATOR", selfNodeId + "→" + peer.getNodeId(), ts);
        }
    }

    private void cancelTimeout() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Retorna los nodos ALIVE cuyo nodeId es lexicográficamente mayor que el propio.
     * En el esquema "node1"/"node2"/"node3", el mayor ID gana la elección.
     */
    private List<NodeInfo> getHigherIdAliveNodes() {
        return membershipManager.getAliveNodes().stream()
                .filter(n -> n.getNodeId().compareTo(selfNodeId) > 0)
                .toList();
    }

    private void log(String msg) {
        System.out.printf("[%s][Bully] %s%n", selfNodeId, msg);
    }
}
