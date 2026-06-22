package whatsapp.server.messages;

import java.io.Serializable;
import java.util.UUID;

/**
 * Clase base abstracta para todos los mensajes intercambiados entre nodos
 * servidores.
 *
 * <p>Todo mensaje inter-nodo debe incluir identificador único, nodo origen, nodo
 * destino, tipo de mensaje, timestamp lógico y timestamp físico local para
 * métricas.</p>
 *
 * <p>El campo {@code sentAtMillis} solo debe usarse para métricas de latencia.
 * No debe usarse para ordenar eventos distribuidos. Para orden distribuido debe
 * utilizarse {@code lamportTimestamp}.</p>
 */
public abstract class NodeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String messageId;
    private final String sourceNodeId;
    private final String targetNodeId;
    private final NodeMessageType type;
    private final long lamportTimestamp;
    private final long sentAtMillis;

    /**
     * Construye un mensaje base entre nodos.
     *
     * @param sourceNodeId nodo emisor del mensaje
     * @param targetNodeId nodo receptor del mensaje
     * @param type tipo lógico del mensaje
     * @param lamportTimestamp marca lógica de Lamport asociada al envío
     * @throws IllegalArgumentException si los campos obligatorios son inválidos
     */
    protected NodeMessage(
            String sourceNodeId,
            String targetNodeId,
            NodeMessageType type,
            long lamportTimestamp
    ) {
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            throw new IllegalArgumentException("sourceNodeId no puede ser vacío");
        }
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw new IllegalArgumentException("targetNodeId no puede ser vacío");
        }
        if (type == null) {
            throw new IllegalArgumentException("type no puede ser null");
        }

        this.messageId = sourceNodeId + "-" + UUID.randomUUID();
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.type = type;
        this.lamportTimestamp = lamportTimestamp;
        this.sentAtMillis = System.currentTimeMillis();
    }

    /**
     * @return identificador único del mensaje
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * @return identificador lógico del nodo emisor
     */
    public String getSourceNodeId() {
        return sourceNodeId;
    }

    /**
     * @return identificador lógico del nodo destino
     */
    public String getTargetNodeId() {
        return targetNodeId;
    }

    /**
     * @return tipo lógico del mensaje
     */
    public NodeMessageType getType() {
        return type;
    }

    /**
     * @return timestamp lógico de Lamport asociado al mensaje
     */
    public long getLamportTimestamp() {
        return lamportTimestamp;
    }

    /**
     * @return timestamp físico local usado solo para métricas
     */
    public long getSentAtMillis() {
        return sentAtMillis;
    }

    /**
     * @return representación textual útil para logs y depuración
     */
    @Override
    public String toString() {
        return "NodeMessage{" +
                "messageId='" + messageId + '\'' +
                ", source='" + sourceNodeId + '\'' +
                ", target='" + targetNodeId + '\'' +
                ", type=" + type +
                ", L=" + lamportTimestamp +
                '}';
    }
}