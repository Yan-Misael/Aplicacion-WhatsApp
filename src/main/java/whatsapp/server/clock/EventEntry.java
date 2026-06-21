package whatsapp.server.clock;

/**
 * Entrada inmutable en el log de eventos lógicos de un nodo.
 *
 * <p>Cada evento relevante del sistema (envío, recepción, operación local)
 * queda registrado con su marca de Lamport para poder reconstruir el orden
 * causal en el informe.</p>
 */
public record EventEntry(
        long seqNo,
        long lamportTimestamp,
        String nodeId,
        String eventKind,
        String messageType,
        String details
) implements Comparable<EventEntry> {

    /**
     * Ordena por timestamp de Lamport; usa el número de secuencia como
     * desempate para eventos del mismo nodo con el mismo valor lógico.
     */
    @Override
    public int compareTo(EventEntry o) {
        int cmp = Long.compare(this.lamportTimestamp, o.lamportTimestamp);
        return cmp != 0 ? cmp : Long.compare(this.seqNo, o.seqNo);
    }

    @Override
    public String toString() {
        return String.format("%-5d L=%-5d [%-6s] %-8s %-28s %s",
                seqNo, lamportTimestamp, nodeId, eventKind, messageType, details);
    }
}
