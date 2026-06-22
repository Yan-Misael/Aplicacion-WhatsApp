package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.messages.PeerHelloAckMessage;
import whatsapp.server.messages.PeerHelloMessage;
import whatsapp.server.node.NodeInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Gestiona el envío de mensajes hacia otros nodos y la conexión inicial con peers.
 *
 * <p>Cada envío abre una conexión TCP corta hacia el nodo destino, escribe el
 * {@link NodeMessage} serializado y cierra el socket. Esta estrategia de
 * "conexión por mensaje" simplifica la gestión de estado y es adecuada para
 * el volumen esperado en la demo académica.</p>
 *
 * <p>La cola {@code incomingQueue} permite que {@link PeerMessageHandler} deje
 * mensajes no reconocidos para que Persona 3, 4 y 5 los consuman.</p>
 */
public class PeerConnectionManager {

    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final ExecutorService peerWorkerPool;
    private final int peerSocketTimeoutMs;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    /**
     * Cola de mensajes entrantes no reconocidos, disponible para Persona 3/4/5.
     */
    private final Queue<NodeMessage> incomingQueue = new ConcurrentLinkedQueue<>();

    /**
     * Construye el manager de conexiones salientes.
     *
     * @param selfNodeId          identificador del nodo local
     * @param membershipManager   membresía del nodo
     * @param peerWorkerPool      pool de hilos para operaciones de peers
     * @param peerSocketTimeoutMs timeout de socket para peers (ms)
     * @param lamportClock        reloj lógico de Lamport del nodo
     * @param eventLogger         logger de eventos con marca lógica
     */
    public PeerConnectionManager(
            String selfNodeId,
            MembershipManager membershipManager,
            ExecutorService peerWorkerPool,
            int peerSocketTimeoutMs,
            LamportClock lamportClock,
            EventLogger eventLogger
    ) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.peerWorkerPool = peerWorkerPool;
        this.peerSocketTimeoutMs = peerSocketTimeoutMs;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /**
     * Envía un mensaje a un nodo específico por TCP.
     *
     * <p>Si el nodo no está en la membresía o no responde, se registra el error
     * y el nodo se marca como SUSPECTED. El proceso local no se detiene.</p>
     *
     * @param targetNodeId identificador lógico del nodo destino
     * @param message      mensaje a enviar
     */
    public void sendToNode(String targetNodeId, NodeMessage message) {
        NodeInfo target = membershipManager.getNode(targetNodeId).orElse(null);

        if (target == null) {
            log("Nodo desconocido en membresía: " + targetNodeId + " — mensaje descartado");
            return;
        }

        peerWorkerPool.submit(() -> doSend(target, message));
    }

    /**
     * Envía un mensaje a todos los nodos conocidos en la membresía.
     *
     * @param message mensaje a difundir
     */
    public void broadcastToPeers(NodeMessage message) {
        for (NodeInfo peer : membershipManager.getAllNodes()) {
            if (!peer.getNodeId().equals(selfNodeId)) {
                sendToNode(peer.getNodeId(), message);
            }
        }
    }

    /** Número máximo de intentos de PEER_HELLO al arranque. */
    private static final int MAX_HELLO_RETRIES = 3;

    /** Espera entre reintentos de PEER_HELLO en ms. */
    private static final long HELLO_RETRY_DELAY_MS = 1_000;

    /**
     * Conecta con los peers configurados inicialmente enviando {@code PEER_HELLO}.
     *
     * <p>Si un peer no está disponible, reintenta hasta {@value #MAX_HELLO_RETRIES}
     * veces con un retardo de {@value #HELLO_RETRY_DELAY_MS} ms entre intentos.
     * Tras agotar los reintentos, el peer se marca como SUSPECTED y el arranque
     * continúa.</p>
     */
    public void connectToInitialPeers() {
        for (NodeInfo peer : membershipManager.getAllNodes()) {
            if (peer.getNodeId().equals(selfNodeId)) {
                continue;
            }

            log("Enviando PEER_HELLO a " + peer.getNodeId() + " " + peer.getHost() + ":" + peer.getPeerPort());

            long ts = lamportClock.tick();
            PeerHelloMessage hello = new PeerHelloMessage(
                    selfNodeId,
                    peer.getNodeId(),
                    ts,
                    membershipManager.getSelf(),
                    membershipManager.getAllNodes()
            );
            eventLogger.logSend("PEER_HELLO", selfNodeId + "→" + peer.getNodeId(), ts);

            peerWorkerPool.submit(() -> doSendAndReadAckWithRetry(peer, hello));
        }
    }

    /**
     * Comprueba si un nodo es alcanzable intentando una conexión TCP rápida.
     *
     * @param nodeId identificador del nodo a comprobar
     * @return {@code true} si el nodo responde al intento de conexión
     */
    public boolean isReachable(String nodeId) {
        NodeInfo target = membershipManager.getNode(nodeId).orElse(null);

        if (target == null) {
            return false;
        }

        try (Socket s = new Socket(target.getHost(), target.getPeerPort())) {
            s.setSoTimeout(peerSocketTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Encola un mensaje entrante no reconocido para consumo posterior.
     *
     * <p>Persona 3, 4 y 5 pueden llamar a {@link #pollIncoming()} para procesar
     * mensajes de su responsabilidad.</p>
     *
     * @param message mensaje a encolar
     */
    public void enqueueIncoming(NodeMessage message) {
        incomingQueue.offer(message);
    }

    /**
     * Extrae el siguiente mensaje entrante pendiente, o {@code null} si no hay.
     *
     * @return siguiente mensaje o {@code null}
     */
    public NodeMessage pollIncoming() {
        return incomingQueue.poll();
    }

    // -------------------------------------------------------------------------
    // Métodos internos de red
    // -------------------------------------------------------------------------

    /**
     * Intenta enviar PEER_HELLO con reintentos.
     */
    private void doSendAndReadAckWithRetry(NodeInfo target, PeerHelloMessage hello) {
        for (int attempt = 1; attempt <= MAX_HELLO_RETRIES; attempt++) {
            try {
                doSendAndReadAck(target, hello);
                return; // éxito
            } catch (Exception e) {
                log("Intento " + attempt + "/" + MAX_HELLO_RETRIES
                        + " fallido para " + target.getNodeId() + ": " + e.getMessage());
                if (attempt < MAX_HELLO_RETRIES) {
                    try {
                        Thread.sleep(HELLO_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log("ERROR: no se pudo conectar con " + target.getNodeId()
                + " tras " + MAX_HELLO_RETRIES + " intentos — marcado SUSPECTED");
        membershipManager.markSuspected(target.getNodeId());
    }

    /**
     * Envía un mensaje TCP y espera el ACK en el caso de PEER_HELLO.
     */
    private void doSendAndReadAck(NodeInfo target, PeerHelloMessage hello) {
        try (Socket socket = new Socket(target.getHost(), target.getPeerPort())) {
            socket.setSoTimeout(peerSocketTimeoutMs);

            // OOS primero + flush antes de OIS para evitar deadlock de headers
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            out.writeObject(hello);
            out.flush();

            log("PEER_HELLO enviado a " + target.getNodeId());

            Object raw = in.readObject();

            if (raw instanceof PeerHelloAckMessage) {
                PeerHelloAckMessage ack = (PeerHelloAckMessage) raw;
                long updatedTs = lamportClock.update(ack.getLamportTimestamp());
                eventLogger.logReceive(
                        "PEER_HELLO_ACK",
                        target.getNodeId() + "→" + selfNodeId,
                        updatedTs
                );

                if (ack.isAccepted()) {
                    membershipManager.markAlive(target.getNodeId());
                    membershipManager.addOrUpdateNode(ack.getReceiverNodeInfo());

                    for (NodeInfo peer : ack.getKnownPeers()) {
                        if (!peer.getNodeId().equals(selfNodeId)) {
                            membershipManager.addOrUpdateNode(peer);
                        }
                    }

                    log("PEER_HELLO_ACK recibido desde " + target.getNodeId()
                            + " L=" + updatedTs);
                    log("Peer detectado: " + target.getNodeId());
                } else {
                    log("PEER_HELLO rechazado por " + target.getNodeId());
                    membershipManager.markSuspected(target.getNodeId());
                }
            } else {
                log("Respuesta inesperada de " + target.getNodeId() + ": " + raw);
            }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Envía un único mensaje TCP sin esperar respuesta (fire-and-forget).
     *
     * <p>Aunque no leamos la respuesta, debemos crear ObjectInputStream para
     * consumir el header OOS que el receptor envía. Sin esto, el receptor
     * obtiene EOFException al crear su propio ObjectInputStream (porque el
     * emisor cierra el socket antes de que se intercambien los headers),
     * lo que genera "Error procesando mensaje de peer: null" en los logs.</p>
     */
    private void doSend(NodeInfo target, NodeMessage message) {
        try (Socket socket = new Socket(target.getHost(), target.getPeerPort())) {
            socket.setSoTimeout(peerSocketTimeoutMs);

            // Mismo orden simétrico que el receptor: OOS+flush primero, luego OIS
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream()); // consume header del receptor

            out.writeObject(message);
            out.flush();

            log("Mensaje enviado a " + target.getNodeId()
                    + " type=" + message.getType()
                    + " L=" + message.getLamportTimestamp());

        } catch (IOException e) {
            log("ERROR enviando mensaje a " + target.getNodeId() + ": " + e.getMessage());
            membershipManager.markSuspected(target.getNodeId());
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][PeerConnMgr] %s%n", selfNodeId, msg);
    }
}
