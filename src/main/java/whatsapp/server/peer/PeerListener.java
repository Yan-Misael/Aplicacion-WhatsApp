package whatsapp.server.peer;

import whatsapp.server.membership.MembershipManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Escucha conexiones entrantes desde otros nodos en el puerto inter-nodo.
 *
 * <p>Al aceptar una conexión, delega inmediatamente el procesamiento a
 * {@link PeerMessageHandler} ejecutado en el {@code peerWorkerPool}. El hilo
 * de aceptación nunca procesa mensajes directamente.</p>
 *
 * <p><b>Fix race condition de arranque:</b> el {@link ServerSocket} se abre de
 * forma síncrona en {@link #openServerSocket()}, llamado desde el hilo que
 * arranca el nodo (ver {@code TcpPeerTransport#start()}) ANTES de encolar
 * el bucle de {@code accept()} y ANTES de enviar cualquier PEER_HELLO. Así se
 * garantiza que el puerto ya está escuchando cuando otro nodo (o el propio
 * nodo, esperando el ACK) intenta conectarse por primera vez.</p>
 *
 * <p><b>Fix de starvation del pool:</b> este Runnable ya NO debe enviarse al
 * {@code peerWorkerPool} (un pool fijo y compartido con el resto de mensajes
 * inter-nodo). El bucle de {@code accept()} bloquea indefinidamente y, si se
 * ejecuta dentro del pool, consume uno de sus hilos para siempre, reduciendo
 * la capacidad real disponible para procesar mensajes. Debe ejecutarse en un
 * hilo dedicado (ver {@code TcpPeerTransport#start()}, que lo lanza con un
 * {@code Thread} propio en vez de {@code peerWorkerPool.submit(...)}).</p>
 */
public class PeerListener implements Runnable {

    private final int peerPort;
    private final String selfNodeId;
    private final ExecutorService peerWorkerPool;
    private final MembershipManager membershipManager;
    private final PeerConnectionManager connectionManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private ServerSocket serverSocket;

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

    /**
     * Espera hasta que {@link #openServerSocket()} haya terminado de abrir el
     * socket. Útil para quien arranque el listener en otro hilo y necesite
     * confirmar que ya está listo antes de continuar.
     */
    public void awaitReady() throws InterruptedException {
        readyLatch.await();
    }

    @Override
    public void run() {
        try {
            if (serverSocket == null) {
                // Modo de compatibilidad: si nadie llamó a openServerSocket()
                // antes, lo abrimos aquí (comportamiento previo).
                openServerSocket();
            }

            while (running.get()) {
                try {
                    Socket peerSocket = serverSocket.accept();

                    // Delegar procesamiento al pool — nunca bloquear el hilo de accept
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
                    // Si running=false es un cierre ordenado — salir sin ruido
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

    /**
     * Detiene el listener y cierra el {@link ServerSocket}.
     */
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
