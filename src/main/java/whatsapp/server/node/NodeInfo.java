package whatsapp.server.node;

import java.io.Serializable;
import java.util.Objects;

/**
 * Representa la información básica de un nodo servidor dentro de la arquitectura
 * multiservidor.
 *
 * <p>Un {@code NodeInfo} contiene la identidad lógica del nodo, su dirección de
 * red, sus puertos de comunicación y su estado conocido dentro de la membresía.</p>
 *
 * <p>La identidad principal del nodo es {@code nodeId}. La IP o el host no deben
 * usarse como identidad principal, ya que pueden cambiar entre entornos de
 * ejecución.</p>
 */
public class NodeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String host;
    private final int clientPort;
    private final int peerPort;

    private NodeStatus status;
    private long lastSeenMillis;

    /**
     * Crea una nueva descripción de nodo.
     *
     * @param nodeId identificador lógico único del nodo, por ejemplo {@code node1}
     * @param host host o dirección IP del nodo
     * @param clientPort puerto utilizado para aceptar clientes
     * @param peerPort puerto utilizado para comunicación entre nodos
     * @throws IllegalArgumentException si algún dato obligatorio es inválido
     */
    public NodeInfo(String nodeId, String host, int clientPort, int peerPort) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId no puede ser vacío");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host no puede ser vacío");
        }
        if (clientPort <= 0 || peerPort <= 0) {
            throw new IllegalArgumentException("Los puertos deben ser positivos");
        }

        this.nodeId = nodeId;
        this.host = host;
        this.clientPort = clientPort;
        this.peerPort = peerPort;
        this.status = NodeStatus.ALIVE;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    /**
     * @return identificador lógico del nodo
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @return host o dirección de red del nodo
     */
    public String getHost() {
        return host;
    }

    /**
     * @return puerto usado para conexiones de clientes
     */
    public int getClientPort() {
        return clientPort;
    }

    /**
     * @return puerto usado para comunicación entre nodos
     */
    public int getPeerPort() {
        return peerPort;
    }

    /**
     * @return estado lógico conocido del nodo
     */
    public NodeStatus getStatus() {
        return status;
    }

    /**
     * Actualiza el estado lógico del nodo y refresca su último tiempo conocido.
     *
     * @param status nuevo estado del nodo
     */
    public void setStatus(NodeStatus status) {
        this.status = status;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    /**
     * @return último instante local en que se tuvo evidencia del nodo
     */
    public long getLastSeenMillis() {
        return lastSeenMillis;
    }

    /**
     * Refresca la marca local de última actividad conocida.
     *
     * <p>Este método será útil cuando se reciban heartbeats, ACKs o mensajes
     * válidos desde este nodo.</p>
     */
    public void touch() {
        this.lastSeenMillis = System.currentTimeMillis();
    }

    /**
     * Compara nodos por su identidad lógica.
     *
     * @param o objeto a comparar
     * @return {@code true} si ambos objetos representan el mismo nodo lógico
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NodeInfo)) {
            return false;
        }

        NodeInfo other = (NodeInfo) o;
        return Objects.equals(nodeId, other.nodeId);
    }

    /**
     * @return hash basado en el identificador lógico del nodo
     */
    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    /**
     * @return representación textual compacta del nodo
     */
    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + clientPort + ":" + peerPort + " [" + status + "]";
    }
}