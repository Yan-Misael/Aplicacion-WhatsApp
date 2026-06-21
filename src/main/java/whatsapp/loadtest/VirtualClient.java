package whatsapp.loadtest;

import whatsapp.common.models.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente sintético para la prueba de carga (Sección 3.1 de la pauta).
 *
 * Replica el protocolo real de ClienteNodo (mismos PaqueteLogin/PaqueteMensaje/
 * PaqueteCrearGrupo/PaqueteUnirseGrupo, mismo ObjectOutputStream/ObjectInputStream),
 * pero sin consola, para poder correr decenas en paralelo.
 *
 * Medición de latencia (el protocolo no trae IDs de correlación nativos, se
 * agregan en el contenido del mensaje):
 *  - LOGIN / CREATE_GROUP / JOIN_GROUP: el servidor responde PaqueteConfirm o
 *    PaqueteError (ManejadorCliente/MessageRouter) -> latencia = round-trip real.
 *  - PRIVATE_PING: este cliente envía "PING|<reqId>" a su "buddy"; el buddy
 *    responde "PONG|<reqId>" al recibirlo -> round-trip real entre nodos.
 *  - GROUP_DELIVERY: el emisor envía "GMSG|<epochMillisEnvio>"; cada miembro que
 *    lo recibe calcula (ahora - epochMillisEnvio) -> latencia de entrega
 *    unidireccional (válido en localhost, sin skew de reloj real).
 */
public class VirtualClient {

    private final String clientId;
    private final String host;
    private final int port;
    private final MetricsRecorder metrics;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Thread listenerThread;

    private volatile String buddyId;          // a quién le hago PING privado
    private volatile boolean loggedIn = false;
    private volatile boolean running = true;

    // Cola de respuestas de control (Confirm/Error) para login/creategroup/joingroup.
    // Cada cliente ejecuta sus operaciones secuencialmente -> a lo sumo UNA
    // operación de control pendiente a la vez, no hace falta más correlación.
    private final BlockingQueue<PaqueteRed> controlResponses = new LinkedBlockingQueue<>();

    // reqId -> nanoTime de envío, para resolver el PONG correspondiente al PING.
    private final Map<String, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicInteger reqSeq = new AtomicInteger(0);

    public VirtualClient(String clientId, String host, int port, MetricsRecorder metrics) {
        this.clientId = clientId;
        this.host = host;
        this.port = port;
        this.metrics = metrics;
    }

    public String getClientId() { return clientId; }
    public void setBuddy(String buddyId) { this.buddyId = buddyId; }
    public boolean isLoggedIn() { return loggedIn; }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    public boolean connectAndLogin() {
        try {
            socket = new Socket(host, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            listenerThread = new Thread(this::listenLoop, "listener-" + clientId);
            listenerThread.setDaemon(true);
            listenerThread.start();

            long t0 = System.nanoTime();
            out.writeObject(new PaqueteLogin(clientId));
            out.flush();

            PaqueteRed resp = controlResponses.poll(5, TimeUnit.SECONDS);
            double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;

            if (resp instanceof PaqueteConfirm) {
                loggedIn = true;
                metrics.recordSuccess(clientId, "LOGIN", latencyMs);
                return true;
            }
            metrics.recordError(clientId, "LOGIN",
                    resp == null ? "timeout" : ((PaqueteError) resp).getRazon());
            return false;
        } catch (IOException | InterruptedException e) {
            metrics.recordError(clientId, "LOGIN", e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (loggedIn) {
                out.writeObject(new PaqueteLogout(clientId));
                out.flush();
            }
        } catch (IOException ignored) {
        }
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Operaciones de carga (cada una se mide y registra en MetricsRecorder)
    // -------------------------------------------------------------------------

    /** Crea un grupo único bajo demanda; mantiene tráfico de Ricart-Agrawala sobre
     *  GROUP_REGISTRY durante toda la ventana de carga, no solo en el setup inicial. */
    public void doCreateGroup(String groupId) {
        controlOp(new PaqueteCrearGrupo(clientId, groupId), "CREATE_GROUP");
    }

    public void doJoinGroup(String groupId) {
        controlOp(new PaqueteUnirseGrupo(clientId, groupId), "JOIN_GROUP");
    }

    private void controlOp(PaqueteRed paquete, String opType) {
        try {
            long t0 = System.nanoTime();
            out.writeObject(paquete);
            out.flush();
            PaqueteRed resp = controlResponses.poll(5, TimeUnit.SECONDS);
            double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;

            if (resp instanceof PaqueteConfirm) {
                metrics.recordSuccess(clientId, opType, latencyMs);
            } else {
                metrics.recordError(clientId, opType,
                        resp == null ? "timeout" : ((PaqueteError) resp).getRazon());
            }
        } catch (IOException | InterruptedException e) {
            metrics.recordError(clientId, opType, e.getMessage());
        }
    }

    /** Ping-pong privado con el buddy asignado. Mide latencia real cruzando nodos. */
    public void doPrivatePing() {
        if (buddyId == null) return;
        String reqId = clientId + "-" + reqSeq.incrementAndGet();
        pendingPings.put(reqId, System.nanoTime());
        try {
            out.writeObject(new PaqueteMensaje(clientId, buddyId, "PING|" + reqId, false));
            out.flush();
            // La latencia se registra al llegar el PONG (ver listenLoop), o se
            // descarta como error si no llega (ver purgeStalePings).
        } catch (IOException e) {
            pendingPings.remove(reqId);
            metrics.recordError(clientId, "PRIVATE_PING", e.getMessage());
        }
    }

    /** Mensaje grupal fire-and-forget; la latencia de entrega la mide cada receptor. */
    public void doGroupMessage(String groupId) {
        try {
            out.writeObject(new PaqueteMensaje(clientId, groupId, "GMSG|" + System.currentTimeMillis(), true));
            out.flush();
            // routeGroupMessage no envía ack de éxito al emisor; el éxito real lo
            // confirma la latencia de entrega que registran los receptores.
        } catch (IOException e) {
            metrics.recordError(clientId, "GROUP_MESSAGE", e.getMessage());
        }
    }

    /** Descarta PINGs sin PONG (ej: el buddy se cayó junto con el nodo derribado). */
    public void purgeStalePings(long timeoutMs) {
        long now = System.nanoTime();
        pendingPings.entrySet().removeIf(e -> {
            boolean stale = (now - e.getValue()) / 1_000_000.0 > timeoutMs;
            if (stale) metrics.recordError(clientId, "PRIVATE_PING", "sin respuesta (timeout)");
            return stale;
        });
    }

    // -------------------------------------------------------------------------
    // Hilo de escucha: igual que ClienteNodo.escucharServidor(), pero despachando
    // PING/PONG/GMSG además de los PaqueteConfirm/PaqueteError de control.
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
                // Esperable para los clientes pegados al nodo que el equipo derribe
                // a propósito durante la prueba (Sección 3.3).
                metrics.recordError(clientId, "CONNECTION", "desconectado: " + e.getMessage());
            }
        }
    }

    private void handleIncomingMessage(PaqueteMensaje msj) {
        String contenido = msj.getContenido();

        if (!msj.isEsGrupo() && contenido.startsWith("PING|")) {
            // Soy el buddy: respondo PONG inmediatamente con el mismo reqId.
            String reqId = contenido.substring("PING|".length());
            try {
                out.writeObject(new PaqueteMensaje(clientId, msj.getIdRemitente(), "PONG|" + reqId, false));
                out.flush();
            } catch (IOException e) {
                metrics.recordError(clientId, "PRIVATE_PING_REPLY", e.getMessage());
            }
        } else if (!msj.isEsGrupo() && contenido.startsWith("PONG|")) {
            // Me llegó la respuesta a un PING que yo mismo envié.
            String reqId = contenido.substring("PONG|".length());
            Long t0 = pendingPings.remove(reqId);
            if (t0 != null) {
                double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;
                metrics.recordSuccess(clientId, "PRIVATE_PING", latencyMs);
            }
        } else if (msj.isEsGrupo() && contenido.startsWith("GMSG|")) {
            long sentAt = Long.parseLong(contenido.substring("GMSG|".length()));
            double latencyMs = System.currentTimeMillis() - sentAt;
            metrics.recordSuccess(clientId, "GROUP_DELIVERY", latencyMs);
        }
    }
}