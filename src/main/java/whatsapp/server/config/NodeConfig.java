package whatsapp.server.config;

import whatsapp.server.node.NodeInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Representa la configuración de ejecución de un {@code ServerNode}.
 *
 * <p>Esta clase carga desde un archivo {@code .properties} la identidad del nodo,
 * puertos, peers conocidos, tamaños de pools y timeouts.</p>
 *
 * <p>La configuración permite ejecutar múltiples nodos en una misma máquina
 * durante la demo, usando puertos diferentes para clientes y comunicación
 * inter-nodo.</p>
 */
public class NodeConfig {

    private final String nodeId;
    private final String host;
    private final int clientPort;
    private final int peerPort;
    private final List<NodeInfo> peers;

    private final int clientPoolSize;
    private final int peerPoolSize;
    private final int schedulerPoolSize;
    private final int coordinationPoolSize;

    private final int clientSocketTimeoutMs;
    private final int peerSocketTimeoutMs;
    private final int heartbeatIntervalMs;
    private final int heartbeatTimeoutMs;

    /**
     * Construye una configuración a partir de propiedades ya cargadas.
     *
     * @param props propiedades del nodo
     */
    private NodeConfig(Properties props) {
        this.nodeId = props.getProperty("node.id");
        this.host = props.getProperty("node.host", "localhost");
        this.clientPort = getInt(props, "node.clientPort", 5001);
        this.peerPort = getInt(props, "node.peerPort", 6001);
        this.peers = parsePeers(props.getProperty("node.peers", ""));

        this.clientPoolSize = getInt(props, "pool.clients", 64);
        this.peerPoolSize = getInt(props, "pool.peers", 16);
        this.schedulerPoolSize = getInt(props, "pool.scheduler", 4);
        this.coordinationPoolSize = getInt(props, "pool.coordination", 1);

        this.clientSocketTimeoutMs = getInt(props, "socket.clientTimeoutMs", 30000);
        this.peerSocketTimeoutMs = getInt(props, "socket.peerTimeoutMs", 5000);
        this.heartbeatIntervalMs = getInt(props, "heartbeat.intervalMs", 2000);
        this.heartbeatTimeoutMs = getInt(props, "heartbeat.timeoutMs", 6000);

        validate();
    }

    /**
     * Carga una configuración desde un archivo {@code .properties}.
     *
     * @param path ruta del archivo de configuración
     * @return configuración cargada
     * @throws IOException si el archivo no puede leerse
     */
    public static NodeConfig fromFile(String path) throws IOException {
        Properties props = new Properties();

        try (FileInputStream fis = new FileInputStream(path)) {
            props.load(fis);
        }

        return new NodeConfig(props);
    }

    /**
     * Valida campos obligatorios de configuración.
     *
     * @throws IllegalArgumentException si falta información crítica
     */
    private void validate() {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("Falta node.id en configuración");
        }
        if (clientPort <= 0 || peerPort <= 0) {
            throw new IllegalArgumentException("Puertos inválidos");
        }
    }

    /**
     * Lee un entero desde propiedades, usando valor por defecto si no existe.
     *
     * @param props propiedades cargadas
     * @param key clave buscada
     * @param defaultValue valor por defecto
     * @return valor entero obtenido
     */
    private static int getInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return Integer.parseInt(value.trim());
    }

    /**
     * Convierte la cadena de peers configurados a objetos {@link NodeInfo}.
     *
     * <p>Formato esperado:</p>
     *
     * <pre>
     * node2@localhost:5002:6002,node3@localhost:5003:6003
     * </pre>
     *
     * @param rawPeers cadena con peers configurados
     * @return lista de peers
     */
    private static List<NodeInfo> parsePeers(String rawPeers) {
        List<NodeInfo> peers = new ArrayList<>();

        if (rawPeers == null || rawPeers.isBlank()) {
            return peers;
        }

        String[] entries = rawPeers.split(",");

        for (String entry : entries) {
            String trimmed = entry.trim();

            if (trimmed.isBlank()) {
                continue;
            }

            String[] idAndAddress = trimmed.split("@");

            if (idAndAddress.length != 2) {
                throw new IllegalArgumentException("Peer inválido: " + trimmed);
            }

            String nodeId = idAndAddress[0];
            String[] addressParts = idAndAddress[1].split(":");

            if (addressParts.length != 3) {
                throw new IllegalArgumentException("Peer inválido: " + trimmed);
            }

            String host = addressParts[0];
            int clientPort = Integer.parseInt(addressParts[1]);
            int peerPort = Integer.parseInt(addressParts[2]);

            peers.add(new NodeInfo(nodeId, host, clientPort, peerPort));
        }

        return peers;
    }

    /**
     * @return representación del nodo actual como {@link NodeInfo}
     */
    public NodeInfo toNodeInfo() {
        return new NodeInfo(nodeId, host, clientPort, peerPort);
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getClientPort() {
        return clientPort;
    }

    public int getPeerPort() {
        return peerPort;
    }

    public List<NodeInfo> getPeers() {
        return peers;
    }

    public int getClientPoolSize() {
        return clientPoolSize;
    }

    public int getPeerPoolSize() {
        return peerPoolSize;
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public int getCoordinationPoolSize() {
        return coordinationPoolSize;
    }

    public int getClientSocketTimeoutMs() {
        return clientSocketTimeoutMs;
    }

    public int getPeerSocketTimeoutMs() {
        return peerSocketTimeoutMs;
    }

    public int getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public int getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }
}