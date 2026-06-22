package whatsapp.server.peer;

import whatsapp.server.clock.EventLogger;
import whatsapp.server.clock.LamportClock;
import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.DistributedGroupManager;
import whatsapp.server.managers.LocalSessionManager;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class PeerListener implements Runnable {
    private final int peerPort;
    private final String selfNodeId;
    private final ExecutorService peerWorkerPool;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;
    private final DistributedGroupManager distributedGroupManager;
    private final LocalSessionManager<ManejadorCliente> localSessionManager;
    private final whatsapp.server.directory.GlobalUserDirectory globalUserDirectory;
    private final LamportClock lamportClock;
    private final EventLogger eventLogger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private ServerSocket serverSocket;

    // Coordinadores inyectados tras creación
    private volatile RicartAgrawalaCoordinator ricartCoordinator;
    private volatile BullyElectionCoordinator bullyCoordinator;

    public PeerListener(
            int peerPort,
            String selfNodeId,
            ExecutorService peerWorkerPool,
            MembershipManager membershipManager,
            PeerConnectionManager connectionManager,
            DistributedGroupManager distributedGroupManager,
            LocalSessionManager<ManejadorCliente> localSessionManager,
            whatsapp.server.directory.GlobalUserDirectory globalUserDirectory,
            LamportClock lamportClock,
            EventLogger eventLogger
    ) {
        this.peerPort = peerPort;
        this.selfNodeId = selfNodeId;
        this.peerWorkerPool = peerWorkerPool;
        this.membershipManager = membershipManager;
        this.connectionManager = connectionManager;
        this.distributedGroupManager = distributedGroupManager;
        this.localSessionManager = localSessionManager;
        this.globalUserDirectory = globalUserDirectory;
        this.lamportClock = lamportClock;
        this.eventLogger = eventLogger;
    }

    public void setCoordinators(RicartAgrawalaCoordinator ricart, BullyElectionCoordinator bully) {
        this.ricartCoordinator = ricart;
        this.bullyCoordinator = bully;
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
                            ricartCoordinator,
                            bullyCoordinator,
                            distributedGroupManager,
                            localSessionManager,
                            globalUserDirectory,
                            lamportClock,
                            eventLogger
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
