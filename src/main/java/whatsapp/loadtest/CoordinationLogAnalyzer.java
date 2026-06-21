package whatsapp.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
/**
 * Cuenta los mensajes de coordinación (Ricart-Agrawala + Bully) generados durante
 * una corrida (Sección 3.2: "Cantidad de mensajes que genera el algoritmo de
 * coordinación").
 *
 * Fuente: n1.log/n2.log/n3.log (consola redirigida). Cada línea relevante viene
 * de EventLogger.add(): "[%s][L=%-4d] %-8s %-28s %s%n"
 *   -> [node1][L=123 ] SEND     MUTEX_REQUEST                node1→node2
 *
 * Se cuentan SOLO líneas SEND: cada mensaje produce un SEND en el emisor y un
 * RECEIVE en el receptor con el mismo messageType (ver PeerMessageHandler.run()),
 * contar ambos duplicaría el conteo.
 *
 * LIMITACIÓN: estas líneas no llevan timestamp de reloj de pared, solo marca de
 * Lamport, así que este analizador da el TOTAL del archivo, no segmentado en
 * Normal/Con caída/Después de recuperación. Para esas 3 cifras por separado,
 * corre 3 ventanas de prueba distintas con LoadGenerator y analiza cada tanda de
 * logs por separado.
 *
 * Uso: java whatsapp.loadtest.CoordinationLogAnalyzer n1.log n2.log n3.log
 */
public class CoordinationLogAnalyzer {

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^\\[(\\w+)]\\[L=\\s*(\\d+)\\s*]\\s+(SEND|RECEIVE|LOCAL)\\s+(\\S+)\\s+(.*)$");

    private static final Set<String> COORDINATION_TYPES = Set.of(
            "MUTEX_REQUEST", "MUTEX_REPLY",
            "ELECTION", "ELECTION_OK", "ELECTION_COORDINATOR"
    );

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Uso: java whatsapp.loadtest.CoordinationLogAnalyzer n1.log n2.log n3.log");
            return;
        }

        Map<String, Integer> totalsByType = new TreeMap<>();
        Map<String, Integer> totalsByNode = new TreeMap<>();
        int grandTotal = 0;

        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.exists(path)) {
                System.err.println("[Aviso] No existe: " + path);
                continue;
            }
            for (String line : readLinesLenient(path)) {
                Matcher m = LINE_PATTERN.matcher(line);
                if (!m.matches()) continue;

                String nodeId = m.group(1);
                String kind = m.group(3);
                String type = m.group(4);

                if (!"SEND".equals(kind) || !COORDINATION_TYPES.contains(type)) continue;

                totalsByType.merge(type, 1, Integer::sum);
                totalsByNode.merge(nodeId, 1, Integer::sum);
                grandTotal++;
            }
        }

        System.out.println("=== Mensajes de coordinación generados (SEND) ===");
        totalsByType.forEach((type, count) -> System.out.printf("  %-22s %d%n", type, count));
        System.out.println("--- por nodo ---");
        totalsByNode.forEach((node, count) -> System.out.printf("  %-10s %d%n", node, count));
        System.out.println("--------------------------------------------------");
        System.out.printf("TOTAL: %d mensajes de coordinación%n", grandTotal);
    }
private static List<String> readLinesLenient(Path path) throws IOException {
    try {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (java.nio.charset.MalformedInputException e) {
        try {
            return Files.readAllLines(path, Charset.forName("windows-1252"));
        } catch (java.nio.charset.MalformedInputException e2) {
            try {
                return Files.readAllLines(path, Charset.forName("IBM850"));
            } catch (java.nio.charset.MalformedInputException e3) {
                return Files.readAllLines(path, StandardCharsets.ISO_8859_1);
            }
        }
    }
}

}

