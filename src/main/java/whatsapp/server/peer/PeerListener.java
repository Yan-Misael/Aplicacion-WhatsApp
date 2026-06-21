package whatsapp.server.peer;

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

    // Nuevos campos
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final GlobalUserDirectory globalUserDirectory;
    private final DistributedGroupManager distributedGroupManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private ServerSocket serverSocket;

    public PeerListener(
            int peerPort,
            String selfNodeId,
            ExecutorService peerWorkerPool,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            LocalSessionManager<ManejadorCliente> localSessionManager,
            GlobalUserDirectory globalUserDirectory,
            DistributedGroupManager distributedGroupManager
    ) {
        this.peerPort = peerPort;
        this.selfNodeId = selfNodeId;
        this.peerWorkerPool = peerWorkerPool;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.distributedGroupManager = distributedGroupManager;
    }

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
                            selfNodeId,
                            localSessionManager,      // ← inyectado
                            globalUserDirectory,      // ← inyectado
                            distributedGroupManager   // ← inyectado
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