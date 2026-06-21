package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.messages.NodeMessage;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;

import java.util.concurrent.ExecutorService;

/**
 * Implementación TCP real de {@link PeerTransport}.
 *
 * <p>Reemplaza {@link NoOpPeerTransport} en ejecución normal. Coordina:</p>
 * <ul>
 *     <li>{@link PeerListener} — recepción de conexiones entrantes.</li>
 *     <li>{@link PeerConnectionManager} — envío de mensajes salientes.</li>
 * </ul>
 *
 * <p>El método {@link #start()} levanta el {@link PeerListener} en el
 * {@code peerWorkerPool} y luego envía {@code PEER_HELLO} a los peers conocidos.
 * Hay un pequeño retardo opcional para dar tiempo a que los otros nodos levanten
 * su listener antes del saludo inicial.</p>
 */
public class TcpPeerTransport implements PeerTransport {

    private final String selfNodeId;
    private final int peerPort;
    private final ExecutorService peerWorkerPool;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;
    private final PeerListener peerListener;

    /** Hilo dedicado del PeerListener (no usa el peerWorkerPool — ver fix Problema 2). */
    private Thread listenerThread;

    /**
     * Construye el transporte TCP.
     *
     * @param selfNodeId              identificador del nodo local
     * @param peerPort                puerto en el que escuchar peers entrantes
     * @param peerWorkerPool          pool de hilos para operaciones inter-nodo
     * @param membershipManager       membresía del nodo
     * @param peerSocketTimeoutMs     timeout de socket para conexiones a peers (ms)
     * @param lamportClock            reloj lógico de Lamport
     * @param eventLogger             logger de eventos distribuidos
     * @param distributedGroupManager gestor de grupos distribuidos
     * @param localSessionManager     sesiones locales de clientes
     */
    public TcpPeerTransport(
            String selfNodeId,
            int peerPort,
            ExecutorService peerWorkerPool,
            MembershipManager membershipManager,
            int peerSocketTimeoutMs,
            LamportClock lamportClock,
            EventLogger eventLogger,
            DistributedGroupManager distributedGroupManager,
            LocalSessionManager<ManejadorCliente> localSessionManager,
            whatsapp.server.directory.GlobalUserDirectory globalUserDirectory
    ) {
        this.selfNodeId = selfNodeId;
        this.peerPort = peerPort;
        this.peerWorkerPool = peerWorkerPool;
        this.membershipManager = membershipManager;

        this.connectionManager = new PeerConnectionManager(
                selfNodeId,
                membershipManager,
                peerWorkerPool,
                peerSocketTimeoutMs,
                lamportClock,
                eventLogger
        );

        this.peerListener = new PeerListener(
                peerPort,
                selfNodeId,
                peerWorkerPool,
                membershipManager,
                connectionManager,
                distributedGroupManager,
                localSessionManager,
                globalUserDirectory,
                lamportClock,
                eventLogger
        );
    }

    // -------------------------------------------------------------------------
    // PeerTransport
    // -------------------------------------------------------------------------

    /**
     * Inicia el listener inter-nodo y conecta con los peers iniciales.
     *
     * <p><b>Fix Problema 1 (race condition):</b> el {@code ServerSocket} del
     * {@link PeerListener} se abre de forma SÍNCRONA aquí, en el hilo que
     * llama a {@code start()}, mediante {@link PeerListener#openServerSocket()}.
     * Solo después de que el socket está realmente escuchando se lanza el
     * bucle de {@code accept()} y se envían los PEER_HELLO. Esto elimina la
     * ventana en la que un nodo (incluido el propio, esperando su ACK)
     * intentaba conectarse antes de que el puerto estuviera abierto.</p>
     *
     * <p><b>Fix Problema 2 (thread starvation):</b> el {@link PeerListener}
     * ya NO se envía al {@code peerWorkerPool} (que es un pool fijo
     * compartido con el resto de mensajes inter-nodo). Se ejecuta en un
     * {@link Thread} dedicado, daemon, para que el bucle bloqueante de
     * {@code accept()} no consuma permanentemente uno de los hilos que el
     * pool necesita para procesar mensajes entrantes/salientes.</p>
     */
    @Override
    public void start() {
        log("Iniciando TcpPeerTransport en puerto " + peerPort);

        try {
            // 1) Abrir el ServerSocket de forma síncrona ANTES de continuar.
            peerListener.openServerSocket();
        } catch (java.io.IOException e) {
            log("ERROR FATAL: no se pudo abrir el puerto de peers " + peerPort
                    + ": " + e.getMessage());
            throw new RuntimeException(e);
        }

        // 2) Lanzar el bucle de accept() en un hilo dedicado, NO en el pool.
        listenerThread = new Thread(peerListener, "peer-listener-" + selfNodeId);
        listenerThread.setDaemon(true);
        listenerThread.start();

        // 3) Solo ahora, con el socket garantizado abierto, conectar a los peers.
        connectionManager.connectToInitialPeers();

        log("TcpPeerTransport iniciado");
    }

    /**
     * Detiene el listener y libera recursos de red.
     */
    @Override
    public void stop() {
        log("Deteniendo TcpPeerTransport");
        peerListener.stop();

        if (listenerThread != null) {
            listenerThread.interrupt();
        }

        log("TcpPeerTransport detenido");
    }

    /**
     * Envía un mensaje a un nodo específico.
     *
     * @param targetNodeId identificador del nodo destino
     * @param message      mensaje a enviar
     */
    @Override
    public void sendToNode(String targetNodeId, NodeMessage message) {
        connectionManager.sendToNode(targetNodeId, message);
    }

    /**
     * Envía un mensaje a todos los peers conocidos y vivos.
     *
     * @param message mensaje a difundir
     */
    @Override
    public void broadcast(NodeMessage message) {
        connectionManager.broadcastToPeers(message);
    }

    /**
     * Inyecta los coordinadores de coordinación distribuida en el {@link PeerListener}.
     * Debe llamarse ANTES de {@link #start()}.
     */
    public void setCoordinators(RicartAgrawalaCoordinator ricart, BullyElectionCoordinator bully) {
        peerListener.setCoordinators(ricart, bully);
    }

    // -------------------------------------------------------------------------
    // Acceso al PeerConnectionManager para Persona 3, 4, 5
    // -------------------------------------------------------------------------

    /**
     * Expone el manager de conexiones para que otras personas puedan consumir
     * la cola de mensajes entrantes y enviar mensajes directamente.
     *
     * @return manager de conexiones salientes
     */
    public PeerConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Delega a {@link PeerConnectionManager#pollIncoming()}. Implementa el
     * contrato de {@link PeerTransport}, así que Persona 3/4/5 acceden a la
     * cola de mensajes pendientes sin necesidad de castear a esta clase
     * concreta (fix Problema 3).
     */
    @Override
    public NodeMessage pollIncoming() {
        return connectionManager.pollIncoming();
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private void log(String msg) {
        System.out.printf("[%s][TcpPeerTransport] %s%n", selfNodeId, msg);
    }
}
