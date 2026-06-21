package whatsapp.loadtest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;

/**
 * Genera los 3 gráficos exigidos por la Sección 3.4 de la pauta (throughput,
 * latencia, errores) a partir del CSV que escribe MetricsRecorder. Reemplaza
 * el script plot_metrics.py: todo en Java estándar, sin matplotlib/pandas ni
 * dependencias nuevas en el pom.xml.
 *
 * Uso: java whatsapp.loadtest.MetricsChartGenerator loadtest-results/loadtest_<ts>.csv
 * Salida: throughput.png, latencia.png, errores.png en el directorio actual.
 */
public class MetricsChartGenerator {

    private record Sample(int offsetSec, double latencyMs, boolean success) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java whatsapp.loadtest.MetricsChartGenerator <archivo.csv>");
            return;
        }

        List<Sample> samples = readCsv(Path.of(args[0]));
        if (samples.isEmpty()) {
            System.out.println("[Aviso] CSV vacío o sin registros válidos.");
            return;
        }

        int maxSec = samples.stream().mapToInt(Sample::offsetSec).max().orElse(0);

        // Agrupa por segundo entero (bucket de 1s -> conteo == throughput directo).
        Map<Integer, List<Sample>> bySecond = new TreeMap<>();
        for (int s = 0; s <= maxSec; s++) bySecond.put(s, new ArrayList<>());
        for (Sample s : samples) bySecond.get(s.offsetSec()).add(s);

        double[] throughput = new double[maxSec + 1];
        double[] avgLatency = new double[maxSec + 1];
        double[] p95Latency = new double[maxSec + 1];
        double[] errorRate = new double[maxSec + 1];

        for (int sec = 0; sec <= maxSec; sec++) {
            List<Sample> bucket = bySecond.get(sec);
            if (bucket.isEmpty()) continue;

            double[] latencies = bucket.stream()
                    .filter(Sample::success)
                    .mapToDouble(Sample::latencyMs)
                    .sorted()
                    .toArray();

            throughput[sec] = latencies.length; // bucket = 1s -> conteo = req/s
            avgLatency[sec] = latencies.length == 0 ? 0 : Arrays.stream(latencies).average().orElse(0);
            p95Latency[sec] = percentile(latencies, 95);

            long errors = bucket.stream().filter(s -> !s.success()).count();
            errorRate[sec] = 100.0 * errors / bucket.size();
        }

        int[] xValues = sequence(maxSec + 1);

        renderChart("throughput.png", "Throughput por segundo", "Segundos desde el inicio",
                "Requests/seg exitosos",
                xValues, new double[][]{throughput}, new String[]{"throughput"},
                new Color[]{new Color(0x2E7D32)});

        renderChart("latencia.png", "Latencia en el tiempo", "Segundos desde el inicio",
                "Latencia (ms)",
                xValues, new double[][]{avgLatency, p95Latency}, new String[]{"promedio", "p95"},
                new Color[]{new Color(0x1565C0), new Color(0xEF6C00)});

        renderChart("errores.png", "Tasa de error por segundo", "Segundos desde el inicio",
                "% errores",
                xValues, new double[][]{errorRate}, new String[]{"error %"},
                new Color[]{new Color(0xC62828)});

        System.out.println("Gráficos generados: throughput.png, latencia.png, errores.png");
    }

    // -------------------------------------------------------------------------
    // CSV
    // -------------------------------------------------------------------------

    private static List<Sample> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Sample> out = new ArrayList<>();

        for (int i = 1; i < lines.size(); i++) { // i=1: salta encabezado
            String[] f = lines.get(i).split(",", -1);
            if (f.length < 6) continue;

            long offsetMs = Long.parseLong(f[1]);
            if (offsetMs < 0) continue; // registros de LOGIN/CREATE_GROUP/JOIN previos a metrics.start()

            double latencyMs = Double.parseDouble(f[4]);
            boolean success = Boolean.parseBoolean(f[5]);
            out.add(new Sample((int) (offsetMs / 1000), latencyMs, success));
        }
        return out;
    }

    private static double percentile(double[] sorted, double pct) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }

    private static int[] sequence(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    // -------------------------------------------------------------------------
    // Render: gráfico de líneas genérico (1+ series) dibujado a mano con Graphics2D.
    // Sin librerías externas: ejes, grilla, leyenda y polilíneas todo manual.
    // -------------------------------------------------------------------------

    private static void renderChart(String filename, String title, String xLabel, String yLabel,
                                      int[] xValues, double[][] series, String[] seriesLabels, Color[] colors)
            throws IOException {

        final int width = 900, height = 500;
        final int marginLeft = 70, marginRight = 30, marginTop = 50, marginBottom = 60;
        final int plotW = width - marginLeft - marginRight;
        final int plotH = height - marginTop - marginBottom;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        double xMax = Math.max(1, xValues[xValues.length - 1]);
        double yMax = 0;
        for (double[] s : series) for (double v : s) yMax = Math.max(yMax, v);
        yMax = yMax <= 0 ? 1 : yMax * 1.15; // 15% de margen superior

        // Ejes
        g.setColor(Color.DARK_GRAY);
        g.drawLine(marginLeft, marginTop, marginLeft, marginTop + plotH);
        g.drawLine(marginLeft, marginTop + plotH, marginLeft + plotW, marginTop + plotH);

        // Grilla + ticks Y (5 divisiones)
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int i = 0; i <= 5; i++) {
            double yVal = yMax * i / 5.0;
            int py = marginTop + plotH - (int) (plotH * i / 5.0);
            g.setColor(new Color(230, 230, 230));
            g.drawLine(marginLeft, py, marginLeft + plotW, py);
            g.setColor(Color.DARK_GRAY);
            g.drawString(String.format(Locale.US, "%.1f", yVal), 8, py + 4);
        }

        // Ticks X (máx. 10 etiquetas, espaciadas según el rango)
        int xTickStep = Math.max(1, (int) Math.ceil(xMax / 10.0));
        for (int xv = 0; xv <= xMax; xv += xTickStep) {
            int px = marginLeft + (int) (plotW * xv / xMax);
            g.setColor(new Color(230, 230, 230));
            g.drawLine(px, marginTop, px, marginTop + plotH);
            g.setColor(Color.DARK_GRAY);
            g.drawString(String.valueOf(xv), px - 8, marginTop + plotH + 18);
        }

        // Series
        for (int s = 0; s < series.length; s++) {
            g.setColor(colors[s % colors.length]);
            g.setStroke(new BasicStroke(2f));
            double[] data = series[s];
            int prevX = -1, prevY = -1;
            for (int i = 0; i < data.length && i < xValues.length; i++) {
                int px = marginLeft + (int) (plotW * xValues[i] / xMax);
                int py = marginTop + plotH - (int) (plotH * data[i] / yMax);
                if (prevX >= 0) g.drawLine(prevX, prevY, px, py);
                prevX = px;
                prevY = py;
            }
        }

        // Leyenda
        int legendX = marginLeft + 10, legendY = marginTop + 15;
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int s = 0; s < seriesLabels.length; s++) {
            g.setColor(colors[s % colors.length]);
            g.fillRect(legendX, legendY + s * 18 - 9, 12, 12);
            g.setColor(Color.BLACK);
            g.drawString(seriesLabels[s], legendX + 18, legendY + s * 18);
        }

        // Título y labels de ejes
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(Color.BLACK);
        g.drawString(title, marginLeft, 28);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(xLabel, marginLeft + plotW / 2 - 40, height - 15);

        Graphics2D gRotated = (Graphics2D) g.create();
        gRotated.rotate(-Math.PI / 2);
        gRotated.drawString(yLabel, -(marginTop + plotH / 2) - 30, 18);
        gRotated.dispose();

        g.dispose();
        ImageIO.write(img, "png", new java.io.File(filename));
    }
}