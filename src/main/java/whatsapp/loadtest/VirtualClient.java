package whatsapp.loadtest;

import whatsapp.common.models.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente sintético para la prueba de carga.
 *
 * Corre el mismo protocolo de ClienteNodo, pero sin consola. Esta versión agrega
 * reconexión automática: si cae el nodo al que estaba conectado, el cliente intenta
 * iniciar sesión nuevamente en otro ServerNode vivo. Así la prueba mide recuperación
 * del servicio y no queda dominada por clientes pegados para siempre al nodo caído.
 */
public class VirtualClient {

    public record Endpoint(String host, int port) {
        @Override public String toString() { return host + ":" + port; }
    }

    private final String clientId;
    private final List<Endpoint> endpoints;
    private final MetricsRecorder metrics;

    private final Object connectionLock = new Object();
    private volatile int currentEndpointIndex;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Thread listenerThread;

    private volatile String buddyId;
    private volatile boolean loggedIn = false;
    private volatile boolean running = true;

    private final BlockingQueue<PaqueteRed> controlResponses = new LinkedBlockingQueue<>();
    private final Map<String, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicInteger reqSeq = new AtomicInteger(0);

    public VirtualClient(String clientId, String host, int port, MetricsRecorder metrics) {
        this(clientId, List.of(new Endpoint(host, port)), 0, metrics);
    }

    public VirtualClient(String clientId, List<Endpoint> endpoints, int preferredIndex, MetricsRecorder metrics) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un endpoint de ServerNode");
        }
        this.clientId = clientId;
        this.endpoints = new ArrayList<>(endpoints);
        this.currentEndpointIndex = Math.floorMod(preferredIndex, endpoints.size());
        this.metrics = metrics;
    }

    public String getClientId() { return clientId; }
    public void setBuddy(String buddyId) { this.buddyId = buddyId; }
    public boolean isLoggedIn() { return loggedIn; }

    // -------------------------------------------------------------------------
    // Ciclo de vida y reconexión
    // -------------------------------------------------------------------------

    public boolean connectAndLogin() {
        return reconnectAndLogin("LOGIN");
    }

    /** Intenta reconectar usando primero el endpoint actual y luego los demás. */
    private boolean ensureConnected(String opType) {
        if (!running) return false;
        Socket s = socket;
        if (loggedIn && s != null && s.isConnected() && !s.isClosed()) {
            return true;
        }
        return reconnectAndLogin(opType + "_RECONNECT");
    }

    private boolean reconnectAndLogin(String opTypeForErrors) {
        synchronized (connectionLock) {
            if (!running) return false;
            closeSocketOnly();
            controlResponses.clear();

            int start = currentEndpointIndex;
            for (int attempt = 0; attempt < endpoints.size(); attempt++) {
                int idx = (start + attempt) % endpoints.size();
                Endpoint endpoint = endpoints.get(idx);
                try {
                    socket = new Socket(endpoint.host(), endpoint.port());
                    socket.setTcpNoDelay(true);
                    out = new ObjectOutputStream(socket.getOutputStream());
                    out.flush();
                    in = new ObjectInputStream(socket.getInputStream());

                    listenerThread = new Thread(this::listenLoop, "listener-" + clientId + "-" + endpoint.port());
                    listenerThread.setDaemon(true);
                    listenerThread.start();

                    long t0 = System.nanoTime();
                    sendObjectUnsafe(new PaqueteLogin(clientId));
                    PaqueteRed resp = controlResponses.poll(5, TimeUnit.SECONDS);
                    double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;

                    if (resp instanceof PaqueteConfirm) {
                        loggedIn = true;
                        currentEndpointIndex = idx;
                        metrics.recordSuccess(clientId, opTypeForErrors.equals("LOGIN") ? "LOGIN" : "RECONNECT", latencyMs);
                        return true;
                    }

                    String reason = resp == null ? "timeout" : errorReason(resp);
                    loggedIn = false;
                    closeSocketOnly();
                    metrics.recordError(clientId, opTypeForErrors, "falló login en " + endpoint + ": " + reason);
                } catch (IOException | InterruptedException e) {
                    loggedIn = false;
                    closeSocketOnly();
                    metrics.recordError(clientId, opTypeForErrors, "no conecta a " + endpoint + ": " + e.getMessage());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (loggedIn && out != null) {
                sendObjectUnsafe(new PaqueteLogout(clientId));
            }
        } catch (IOException ignored) {
        }
        closeSocketOnly();
    }

    private void markDisconnected() {
        loggedIn = false;
        closeSocketOnly();
    }

    private void closeSocketOnly() {
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    // -------------------------------------------------------------------------
    // Operaciones de carga
    // -------------------------------------------------------------------------

    public void doCreateGroup(String groupId) {
        controlOp(new PaqueteCrearGrupo(clientId, groupId), "CREATE_GROUP");
    }

    public void doJoinGroup(String groupId) {
        controlOp(new PaqueteUnirseGrupo(clientId, groupId), "JOIN_GROUP");
    }

    private void controlOp(PaqueteRed paquete, String opType) {
        if (!ensureConnected(opType)) {
            metrics.recordError(clientId, opType, "sin conexión a nodo vivo");
            return;
        }
        try {
            long t0 = System.nanoTime();
            sendObject(paquete);
            PaqueteRed resp = controlResponses.poll(5, TimeUnit.SECONDS);
            double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;

            if (resp instanceof PaqueteConfirm) {
                metrics.recordSuccess(clientId, opType, latencyMs);
            } else {
                String reason = resp == null ? "timeout" : errorReason(resp);
                // En pruebas largas puede repetirse una operación ya aplicada antes de perderse la respuesta.
                // Para no inflar falsos negativos, CREATE_GROUP idempotente se considera OK si el grupo ya existe.
                if ("CREATE_GROUP".equals(opType) && reason.toLowerCase().contains("ya existe")) {
                    metrics.recordSuccess(clientId, opType, latencyMs);
                } else {
                    metrics.recordError(clientId, opType, reason);
                }
            }
        } catch (IOException | InterruptedException e) {
            markDisconnected();
            metrics.recordError(clientId, opType, e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Ping-pong privado con el buddy asignado. Mide latencia real cruzando nodos. */
    public void doPrivatePing() {
        if (buddyId == null) return;
        if (!ensureConnected("PRIVATE_PING")) {
            metrics.recordError(clientId, "PRIVATE_PING", "sin conexión a nodo vivo");
            return;
        }

        String reqId = clientId + "-" + reqSeq.incrementAndGet();
        pendingPings.put(reqId, System.nanoTime());
        try {
            sendObject(new PaqueteMensaje(clientId, buddyId, "PING|" + reqId, false));
        } catch (IOException e) {
            pendingPings.remove(reqId);
            markDisconnected();
            metrics.recordError(clientId, "PRIVATE_PING", e.getMessage());
        }
    }

    /** Mensaje grupal fire-and-forget. Se mide aceptación del envío y entrega en receptores. */
    public void doGroupMessage(String groupId) {
        if (!ensureConnected("GROUP_MESSAGE")) {
            metrics.recordError(clientId, "GROUP_MESSAGE", "sin conexión a nodo vivo");
            return;
        }
        long t0 = System.nanoTime();
        try {
            sendObject(new PaqueteMensaje(clientId, groupId, "GMSG|" + System.currentTimeMillis(), true));
            // El protocolo no confirma el mensaje grupal al emisor. Se registra como request aceptada
            // al escribir correctamente al socket; las entregas remotas se registran como GROUP_DELIVERY.
            metrics.recordSuccess(clientId, "GROUP_MESSAGE", (System.nanoTime() - t0) / 1_000_000.0);
        } catch (IOException e) {
            markDisconnected();
            metrics.recordError(clientId, "GROUP_MESSAGE", e.getMessage());
        }
    }

    /** Descarta PINGs sin PONG. */
    public void purgeStalePings(long timeoutMs) {
        long now = System.nanoTime();
        pendingPings.entrySet().removeIf(e -> {
            boolean stale = (now - e.getValue()) / 1_000_000.0 > timeoutMs;
            if (stale) metrics.recordError(clientId, "PRIVATE_PING", "sin respuesta (timeout)");
            return stale;
        });
    }

    // -------------------------------------------------------------------------
    // Hilo de escucha
    // -------------------------------------------------------------------------

    private void listenLoop() {
        try {
            while (running) {
                Object respuesta = in.readObject();
                if (respuesta instanceof PaqueteMensaje msj) {
                    handleIncomingMessage(msj);
                } else if (respuesta instanceof PaqueteConfirm || respuesta instanceof PaqueteError) {
                    controlResponses.offer((PaqueteRed) respuesta);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (running) {
                metrics.recordError(clientId, "CONNECTION", "desconectado: " + e.getMessage());
                markDisconnected();
            }
        }
    }

    private void handleIncomingMessage(PaqueteMensaje msj) {
        String contenido = msj.getContenido();

        if (!msj.isEsGrupo() && contenido.startsWith("PING|")) {
            String reqId = contenido.substring("PING|".length());
            try {
                sendObject(new PaqueteMensaje(clientId, msj.getIdRemitente(), "PONG|" + reqId, false));
            } catch (IOException e) {
                markDisconnected();
                metrics.recordError(clientId, "PRIVATE_PING_REPLY", e.getMessage());
            }
        } else if (!msj.isEsGrupo() && contenido.startsWith("PONG|")) {
            String reqId = contenido.substring("PONG|".length());
            Long t0 = pendingPings.remove(reqId);
            if (t0 != null) {
                double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;
                metrics.recordSuccess(clientId, "PRIVATE_PING", latencyMs);
            }
        } else if (msj.isEsGrupo() && contenido.startsWith("GMSG|")) {
            try {
                long sentAt = Long.parseLong(contenido.substring("GMSG|".length()));
                double latencyMs = Math.max(0, System.currentTimeMillis() - sentAt);
                metrics.recordSuccess(clientId, "GROUP_DELIVERY", latencyMs);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void sendObject(PaqueteRed paquete) throws IOException {
        synchronized (connectionLock) {
            sendObjectUnsafe(paquete);
        }
    }

    private void sendObjectUnsafe(PaqueteRed paquete) throws IOException {
        if (out == null) throw new IOException("socket no disponible");
        out.writeObject(paquete);
        out.flush();
        out.reset();
    }

    private String errorReason(PaqueteRed resp) {
        if (resp instanceof PaqueteError err) return err.getRazon();
        if (resp instanceof PaqueteConfirm conf) return conf.getMensaje();
        return String.valueOf(resp);
    }
}
