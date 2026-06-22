package whatsapp.server.clock;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logger thread-safe de eventos lógicos con marca de Lamport.
 *
 * <p>Registra cada evento relevante del nodo (envíos, recepciones y eventos
 * locales) con su timestamp de Lamport. Al finalizar el nodo, puede imprimir
 * y persistir los eventos ordenados causalmente para el informe.</p>
 */
public class EventLogger {

    private final String nodeId;
    private final ConcurrentLinkedDeque<EventEntry> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong(0);

    public EventLogger(String nodeId) {
        this.nodeId = nodeId;
    }

    // -------------------------------------------------------------------------
    // Métodos de registro
    // -------------------------------------------------------------------------

    /**
     * Registra el envío de un mensaje inter-nodo.
     *
     * @param messageType tipo del mensaje (p.ej. "PEER_HELLO")
     * @param details     descripción del evento (p.ej. "node1→node2")
     * @param lamport     marca de Lamport del mensaje enviado
     */
    public void logSend(String messageType, String details, long lamport) {
        add("SEND", messageType, details, lamport);
    }

    /**
     * Registra la recepción de un mensaje inter-nodo.
     *
     * @param messageType tipo del mensaje recibido
     * @param details     descripción del evento
     * @param lamport     marca de Lamport local actualizada tras la recepción
     */
    public void logReceive(String messageType, String details, long lamport) {
        add("RECEIVE", messageType, details, lamport);
    }

    /**
     * Registra un evento local (p.ej. inicio de sesión de cliente, creación de grupo).
     *
     * @param messageType etiqueta del evento
     * @param details     descripción del evento
     * @param lamport     marca de Lamport al momento del evento
     */
    public void logLocal(String messageType, String details, long lamport) {
        add("LOCAL", messageType, details, lamport);
    }

    // -------------------------------------------------------------------------
    // Salida
    // -------------------------------------------------------------------------

    /**
     * Imprime por consola todos los eventos registrados, ordenados por Lamport.
     */
    public void printSortedByLamport() {
        List<EventEntry> sorted = sortedSnapshot();
        System.out.printf("%n========== LOG DE EVENTOS ORDENADO POR LAMPORT [%s] ==========%n", nodeId);
        System.out.printf("%-5s %-7s %-6s %-8s %-28s %s%n",
                "SEQ", "L", "NODO", "EVENTO", "TIPO", "DETALLE");
        System.out.println("-".repeat(80));
        for (EventEntry e : sorted) {
            System.out.println(e);
        }
        System.out.println("=".repeat(80));
    }

    /**
     * Escribe el log ordenado a un archivo en disco.
     *
     * @param directory directorio donde crear el archivo (se crea si no existe)
     */
    public void flushToFile(Path directory) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("events-" + nodeId + ".log");
            List<EventEntry> sorted = sortedSnapshot();

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
                pw.printf("LOG DE EVENTOS ORDENADO POR LAMPORT [%s]%n", nodeId);
                pw.printf("%-5s %-7s %-6s %-8s %-28s %s%n",
                        "SEQ", "L", "NODO", "EVENTO", "TIPO", "DETALLE");
                pw.println("-".repeat(80));
                for (EventEntry e : sorted) {
                    pw.println(e);
                }
            }
            System.out.printf("[%s][EventLogger] Log escrito en %s (%d eventos)%n",
                    nodeId, file.toAbsolutePath(), sorted.size());

        } catch (IOException e) {
            System.err.printf("[%s][EventLogger] Error al escribir log: %s%n", nodeId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internos
    // -------------------------------------------------------------------------

    private void add(String kind, String type, String details, long lamport) {
        long s = seq.incrementAndGet();
        EventEntry entry = new EventEntry(s, lamport, nodeId, kind, type, details);
        events.addLast(entry);
        System.out.printf("[%s][L=%-4d] %-8s %-28s %s%n", nodeId, lamport, kind, type, details);
    }

    private List<EventEntry> sortedSnapshot() {
        List<EventEntry> copy = new ArrayList<>(events);
        Collections.sort(copy);
        return copy;
    }
}
