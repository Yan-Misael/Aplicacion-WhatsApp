package whatsapp.loadtest;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recolector thread-safe de métricas de la prueba de carga (Sección 3.2 de la pauta).
 *
 * Cada operación ejecutada por un VirtualClient se registra como un RequestRecord
 * inmutable. Al final de la corrida, finalizeAndReport() segmenta los registros en
 * 3 ventanas (Normal / Con caída / Después de recuperación) usando el timestamp
 * de la marca de falla inducida (markFailureInjected()), y calcula throughput,
 * latencia promedio, p95 y tasa de error por ventana — exactamente las columnas
 * de la Tabla de plantilla de resultados del informe (anexos.tex, Anexo D).
 */
public class MetricsRecorder {

    /** Duración de la "ventana de falla" tras la marca, en ms. Ajustar según cuánto tarde la reelección real. */
    private static final long FAILURE_WINDOW_MS = 15_000;

    public record RequestRecord(long epochMillis, String clientId, String opType,
                                 double latencyMs, boolean success, String errorDetail) {}

    private final ConcurrentLinkedQueue<RequestRecord> records = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalAttempts = new AtomicLong(0);

    private volatile long testStartEpochMillis = -1;
    private volatile long testEndEpochMillis = -1;
    private volatile long failureMarkerEpochMillis = -1; // -1 = no se indujo falla (o no se marcó)

    public void start() {
        testStartEpochMillis = System.currentTimeMillis();
    }

    public void stop() {
        testEndEpochMillis = System.currentTimeMillis();
    }

    /** Llamado desde el hilo que escucha el ENTER del operador cuando derriba el nodo/coordinador. */
    public void markFailureInjected() {
        failureMarkerEpochMillis = System.currentTimeMillis();
        System.out.println("\n[MetricsRecorder] Falla inducida marcada en t="
                + (failureMarkerEpochMillis - testStartEpochMillis) + "ms desde el inicio.\n");
    }

    public void recordSuccess(String clientId, String opType, double latencyMs) {
        totalAttempts.incrementAndGet();
        records.add(new RequestRecord(System.currentTimeMillis(), clientId, opType, latencyMs, true, null));
    }

    public void recordError(String clientId, String opType, String detail) {
        totalAttempts.incrementAndGet();
        records.add(new RequestRecord(System.currentTimeMillis(), clientId, opType, -1, false, detail));
    }

    // -------------------------------------------------------------------------
    // Reporte final
    // -------------------------------------------------------------------------

    public void finalizeAndReport(Path csvOutput) {
        List<RequestRecord> all = new ArrayList<>(records);

        writeCsv(csvOutput, all);

        if (failureMarkerEpochMillis < 0) {
            // No hubo falla inducida en esta corrida: una sola ventana "Normal, sin falla"
            printWindow("Normal, sin falla", filter(all, testStartEpochMillis, testEndEpochMillis));
            return;
        }

        long preEnd = failureMarkerEpochMillis;
        long failEnd = failureMarkerEpochMillis + FAILURE_WINDOW_MS;

        printWindow("Normal, sin falla", filter(all, testStartEpochMillis, preEnd));
        printWindow("Con caída del coordinador", filter(all, preEnd, Math.min(failEnd, testEndEpochMillis)));
        printWindow("Después de recuperación", filter(all, Math.min(failEnd, testEndEpochMillis), testEndEpochMillis));
    }

    private List<RequestRecord> filter(List<RequestRecord> all, long fromMs, long toMs) {
        List<RequestRecord> out = new ArrayList<>();
        for (RequestRecord r : all) {
            if (r.epochMillis() >= fromMs && r.epochMillis() < toMs) out.add(r);
        }
        return out;
    }

    private void printWindow(String scenario, List<RequestRecord> window) {
        if (window.isEmpty()) {
            System.out.printf("%-28s | sin datos en esta ventana%n", scenario);
            return;
        }

        long windowMs = window.get(window.size() - 1).epochMillis() - window.get(0).epochMillis();
        double windowSeconds = Math.max(windowMs / 1000.0, 1.0); // evita división por 0 en ventanas muy cortas

        long success = window.stream().filter(RequestRecord::success).count();
        long errors = window.size() - success;

        double[] latencies = window.stream()
                .filter(RequestRecord::success)
                .mapToDouble(RequestRecord::latencyMs)
                .sorted()
                .toArray();

        double avgLatency = latencies.length == 0 ? 0 :
                java.util.Arrays.stream(latencies).average().orElse(0);
        double p95Latency = percentile(latencies, 95);
        double throughput = success / windowSeconds;
        double errorRate = 100.0 * errors / window.size();

        System.out.printf(Locale.US,
                "%-28s | throughput=%8.2f req/s | lat_prom=%8.2f ms | p95=%8.2f ms | errores=%5.2f%% (%d/%d)%n",
                scenario, throughput, avgLatency, p95Latency, errorRate, errors, window.size());
    }

    private double percentile(double[] sortedValues, double pct) {
        if (sortedValues.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sortedValues.length) - 1;
        idx = Math.max(0, Math.min(idx, sortedValues.length - 1));
        return sortedValues[idx];
    }

    private void writeCsv(Path path, List<RequestRecord> all) {
        try {
            Files.createDirectories(path.getParent());
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
                pw.println("epochMillis,offsetMs,clientId,opType,latencyMs,success,errorDetail");
                for (RequestRecord r : all) {
                    pw.printf(Locale.US, "%d,%d,%s,%s,%.3f,%b,%s%n",
                            r.epochMillis(), r.epochMillis() - testStartEpochMillis,
                            r.clientId(), r.opType(), r.latencyMs(), r.success(),
                            r.errorDetail() == null ? "" : r.errorDetail().replace(",", ";"));
                }
            }
            System.out.println("[MetricsRecorder] CSV escrito en " + path.toAbsolutePath()
                    + " (" + all.size() + " registros)");
        } catch (IOException e) {
            System.err.println("[MetricsRecorder] Error escribiendo CSV: " + e.getMessage());
        }
    }
}