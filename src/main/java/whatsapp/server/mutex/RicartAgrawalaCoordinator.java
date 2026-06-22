package whatsapp.server.mutex;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.MutexReplyMessage;
import whatsapp.server.messages.MutexRequestMessage;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.peer.PeerTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implementa el algoritmo de exclusión mutua distribuida de Ricart-Agrawala.
 *
 * <p>Protege el recurso lógico {@code GROUP_REGISTRY}. El algoritmo garantiza
 * que a lo sumo un nodo se encuentra en la sección crítica en un momento dado.</p>
 *
 * <h2>Estados</h2>
 * <ul>
 *   <li>{@link MutexState#RELEASED} — sin interés en la SC.</li>
 *   <li>{@link MutexState#WANTED} — solicitud enviada, esperando permisos.</li>
 *   <li>{@link MutexState#HELD} — dentro de la sección crítica.</li>
 * </ul>
 *
 * <h2>Manejo de nodos caídos</h2>
 * <p>Si un nodo de la lista {@code pendingReplies} cae antes de responder,
 * {@link #onNodeDown(String)} lo remueve del conjunto. Si era el último pendiente,
 * se desbloquea la espera para evitar inanición indefinida.</p>
 */
public class RicartAgrawalaCoordinator {

    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final PeerTransport peerTransport;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    private MutexState state = MutexState.RELEASED;
    private long myRequestTimestamp = 0;

    /** Nodos de los que aún esperamos MUTEX_REPLY en la ronda actual. */
    private final Set<String> pendingReplies = ConcurrentHashMap.newKeySet();

    /** Nodos cuyo MUTEX_REPLY fue diferido mientras estábamos en HELD o con prioridad. */
    private final Queue<String> deferredQueue = new ConcurrentLinkedQueue<>();

    public RicartAgrawalaCoordinator(
            String selfNodeId,
            MembershipManager membershipManager,
            PeerTransport peerTransport,
            LamportClock lamportClock,
            EventLogger eventLogger) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.peerTransport = peerTransport;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /**
     * Adquiere la sección crítica, ejecuta {@code action} y la libera.
     * Bloquea el hilo llamante hasta obtener permisos de todos los peers ALIVE.
     *
     * @param resourceId identificador lógico del recurso (para logging)
     * @param action     código a ejecutar dentro de la sección crítica
     */
    public void executeCriticalSection(String resourceId, Runnable action) {
        try {
            acquire(resourceId);
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrumpido esperando mutex distribuido", e);
        } finally {
            release();
        }
    }

    /**
     * Procesa un {@code MUTEX_REQUEST} entrante.
     * Llamado por {@code PeerMessageHandler}; el reloj de Lamport ya fue actualizado upstream.
     *
     * @param senderId         nodo que solicita la SC
     * @param senderTimestamp  timestamp de Lamport de la solicitud
     */
    public synchronized void onMutexRequest(String senderId, long senderTimestamp) {
        boolean shouldDefer =
                (state == MutexState.HELD) ||
                (state == MutexState.WANTED && hasPriorityOver(senderId, senderTimestamp));

        if (shouldDefer) {
            log("REQUEST de " + senderId + " L=" + senderTimestamp + " → DEFERIDO (estado=" + state + ")");
            deferredQueue.offer(senderId);
        } else {
            log("REQUEST de " + senderId + " L=" + senderTimestamp + " → REPLY inmediato");
            sendReply(senderId);
        }
    }

    /**
     * Procesa un {@code MUTEX_REPLY} entrante.
     * Llamado por {@code PeerMessageHandler}; el reloj de Lamport ya fue actualizado upstream.
     *
     * @param senderId nodo que otorgó permiso
     */
    public synchronized void onMutexReply(String senderId) {
        if (state != MutexState.WANTED) {
            return;
        }
        pendingReplies.remove(senderId);
        log("REPLY de " + senderId + " — pendientes: " + pendingReplies);
        if (pendingReplies.isEmpty()) {
            notifyAll();
        }
    }

    /**
     * Notifica que un nodo cayó. Elimina al nodo de la espera para evitar bloqueo indefinido.
     * Llamado por {@code HeartbeatSweeperTask}.
     *
     * @param nodeId nodo caído
     */
    public synchronized void onNodeDown(String nodeId) {
        boolean removed = pendingReplies.remove(nodeId);
        deferredQueue.remove(nodeId);

        if (removed) {
            log("Nodo caído " + nodeId + " removido de pendingReplies — pendientes: " + pendingReplies);
        }

        if (state == MutexState.WANTED && pendingReplies.isEmpty()) {
            notifyAll();
        }
    }

    // -------------------------------------------------------------------------
    // Métodos internos del algoritmo
    // -------------------------------------------------------------------------

    private synchronized void acquire(String resourceId) throws InterruptedException {
        state = MutexState.WANTED;
        myRequestTimestamp = lamportClock.tick();

        // Snapshot de peers ALIVE en este momento
        List<NodeInfo> aliveNodes = membershipManager.getAliveNodes();
        pendingReplies.clear();
        for (NodeInfo node : aliveNodes) {
            pendingReplies.add(node.getNodeId());
        }

        log("ACQUIRE '" + resourceId + "': REQUEST L=" + myRequestTimestamp
                + " → " + pendingReplies);

        // Enviar MUTEX_REQUEST a todos los peers ALIVE (async, no bloquea aquí)
        List<String> targets = new ArrayList<>(pendingReplies);
        for (String nodeId : targets) {
            MutexRequestMessage req = new MutexRequestMessage(
                    selfNodeId, nodeId, myRequestTimestamp, resourceId);
            peerTransport.sendToNode(nodeId, req);
            eventLogger.logSend("MUTEX_REQUEST", selfNodeId + "→" + nodeId, myRequestTimestamp);
        }

        // Si no hay peers, entramos directamente
        if (pendingReplies.isEmpty()) {
            state = MutexState.HELD;
            log("HELD sin peers ALIVE");
            return;
        }

        // Esperar hasta recibir todos los MUTEX_REPLY (wait() libera el monitor)
        while (!pendingReplies.isEmpty()) {
            wait();
        }

        state = MutexState.HELD;
        log("HELD — todos los replies recibidos");
    }

    private synchronized void release() {
        state = MutexState.RELEASED;
        log("RELEASED — respondiendo a " + deferredQueue.size() + " diferidos");

        while (!deferredQueue.isEmpty()) {
            String nodeId = deferredQueue.poll();
            sendReply(nodeId);
        }
    }

    private void sendReply(String targetNodeId) {
        long ts = lamportClock.tick();
        MutexReplyMessage reply = new MutexReplyMessage(selfNodeId, targetNodeId, ts);
        peerTransport.sendToNode(targetNodeId, reply);
        eventLogger.logSend("MUTEX_REPLY", selfNodeId + "→" + targetNodeId, ts);
    }

    /**
     * Retorna {@code true} si este nodo tiene prioridad sobre la solicitud del sender.
     * Prioridad: timestamp menor gana; en empate, nodeId menor (lexicográfico) gana.
     */
    private boolean hasPriorityOver(String senderId, long senderTimestamp) {
        return myRequestTimestamp < senderTimestamp ||
               (myRequestTimestamp == senderTimestamp && selfNodeId.compareTo(senderId) < 0);
    }

    private void log(String msg) {
        System.out.printf("[%s][Ricart-Agrawala] %s%n", selfNodeId, msg);
    }
}
