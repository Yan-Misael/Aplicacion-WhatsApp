package whatsapp.server.peer;

import whatsapp.server.membership.MembershipManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import whatsapp.server.directory.GlobalUserDirectory;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;

public class PeerListener implements Runnable {
    private final int peerPort;
    private final String selfNodeId;
    private final ExecutorService peerWorkerPool;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private ServerSocket serverSocket;

    // Coordinadores de coordinación distribuida (inyectados tras creación del transporte)
    private volatile RicartAgrawalaCoordinator ricartCoordinator;
    private volatile BullyElectionCoordinator bullyCoordinator;

    /**
     * Construye el listener inter-nodo.
     *
     * @param peerPort          puerto en el que escuchar conexiones de peers
     * @param selfNodeId        identificador del nodo local (para logs)
     * @param peerWorkerPool    pool de hilos para procesar mensajes
     * @param membershipManager membresía del nodo local
     * @param connectionManager manager de conexiones salientes
     */
    public PeerListener(
            int peerPort,
            String selfNodeId,
            ExecutorService peerWorkerPool,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager
    ) {
        this.peerPort = peerPort;
        this.selfNodeId = selfNodeId;
        this.peerWorkerPool = peerWorkerPool;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
    }

    /**
     * Abre el {@link ServerSocket} de forma síncrona en el hilo que llama a
     * este método (normalmente el hilo principal de arranque del nodo).
     *
     * <p>Debe invocarse y completarse ANTES de lanzar {@link #run()} en su
     * hilo dedicado y ANTES de enviar cualquier PEER_HELLO a otros peers.
     * Esto elimina la ventana de carrera en la que un nodo intenta conectarse
     * a un peer cuyo listener aún no está aceptando conexiones.</p>
     *
     * @throws IOException si no se pudo abrir el socket en {@code peerPort}
     */
    public void openServerSocket() throws IOException {
        serverSocket = new ServerSocket(peerPort);
        running.set(true);
        log("PeerListener escuchando en puerto " + peerPort);
        readyLatch.countDown();
    }

    public void awaitReady() throws InterruptedException {
        readyLatch.await();
    }

    @Override
    public void run() {
        try {
            if (serverSocket == null) {
                openServerSocket();
            }

            while (running.get()) {
                try {
                    Socket peerSocket = serverSocket.accept();

                    peerWorkerPool.submit(new PeerMessageHandler(
                            peerSocket,
                            membershipManager,
                            connectionManager,
                            selfNodeId
                    ));

                } catch (SocketException e) {
                    if (running.get()) {
                        log("SocketException en accept: " + e.getMessage());
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log("Error aceptando conexión de peer: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log("No se pudo abrir ServerSocket en puerto " + peerPort + ": " + e.getMessage());
        }
    }

    public void stop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                log("PeerListener detenido");
            } catch (IOException e) {
                log("Error cerrando ServerSocket: " + e.getMessage());
            }
        }
    }

    private void log(String msg) {
        System.out.printf("[%s][PeerListener] %s%n", selfNodeId, msg);
    }
}