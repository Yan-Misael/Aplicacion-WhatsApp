package whatsapp.loadtest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuenta mensajes de coordinación generados durante una corrida.
 *
 * Soporta dos formatos:
 *  1) consola:      [node1][L=123 ] SEND     MUTEX_REQUEST node1→node2
 *  2) EventLogger:  30    L=58    [node1 ] SEND     MUTEX_REQUEST node1→node2
 *
 * Se cuentan solo eventos SEND para no duplicar envío+recepción.
 */
public class CoordinationLogAnalyzer {

    private static final Pattern CONSOLE_PATTERN = Pattern.compile(
            "^\\[(\\w+)]\\[L=\\s*(\\d+)\\s*]\\s+(SEND|RECEIVE|LOCAL)\\s+(\\S+)\\s+(.*)$");

    private static final Pattern EVENT_FILE_PATTERN = Pattern.compile(
            "^\\s*\\d+\\s+L=\\s*(\\d+)\\s+\\[(\\w+)\\s*]\\s+(SEND|RECEIVE|LOCAL)\\s+(\\S+)\\s+(.*)$");

    private static final Set<String> COORDINATION_TYPES = Set.of(
            "MUTEX_REQUEST", "MUTEX_REPLY",
            "ELECTION", "ELECTION_OK", "ELECTION_COORDINATOR"
    );

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Uso: java whatsapp.loadtest.CoordinationLogAnalyzer logs/events-node1.log logs/events-node2.log logs/events-node3.log");
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

            for (String rawLine : readLinesLenient(path)) {
                ParsedLine parsed = parseLine(clean(rawLine));
                if (parsed == null) continue;
                if (!"SEND".equals(parsed.kind())) continue;
                if (!COORDINATION_TYPES.contains(parsed.type())) continue;

                totalsByType.merge(parsed.type(), 1, Integer::sum);
                totalsByNode.merge(parsed.nodeId(), 1, Integer::sum);
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

    private record ParsedLine(String nodeId, String kind, String type) {}

    private static ParsedLine parseLine(String line) {
        Matcher c = CONSOLE_PATTERN.matcher(line);
        if (c.matches()) {
            return new ParsedLine(c.group(1), c.group(3), c.group(4));
        }
        Matcher e = EVENT_FILE_PATTERN.matcher(line);
        if (e.matches()) {
            return new ParsedLine(e.group(2), e.group(3), e.group(4));
        }
        return null;
    }

    private static String clean(String line) {
        if (line == null) return "";
        return line.replace("\uFEFF", "").replace("\u0000", "").trim();
    }

    private static List<String> readLinesLenient(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Charset charset = detectCharset(bytes);
        String text = new String(bytes, charset);
        String[] split = text.split("\\R");
        List<String> lines = new ArrayList<>(split.length);
        for (String s : split) lines.add(s);
        return lines;
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        }
        if (bytes.length >= 3) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) return StandardCharsets.UTF_8;
        }

        // Si hay muchos NUL en posiciones pares/impares, probablemente es UTF-16 sin BOM.
        int evenNul = 0, oddNul = 0, samples = Math.min(bytes.length, 2000);
        for (int i = 0; i < samples; i++) {
            if (bytes[i] == 0) {
                if ((i & 1) == 0) evenNul++; else oddNul++;
            }
        }
        if (oddNul > samples / 10) return StandardCharsets.UTF_16LE;
        if (evenNul > samples / 10) return StandardCharsets.UTF_16BE;

        // Git Bash/tee normalmente deja UTF-8. PowerShell antiguo puede dejar CP1252/850,
        // pero para los tokens ASCII que contamos UTF-8 funciona en la práctica.
        return StandardCharsets.UTF_8;
    }
}
