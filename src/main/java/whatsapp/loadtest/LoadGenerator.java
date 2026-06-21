package whatsapp.loadtest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Generador de carga real para la pauta ICI-4344.
 *
 * Uso:
 *   java whatsapp.loadtest.LoadGenerator [numClientes] [duracionSegundos]
 *
 * Por defecto usa 50 clientes durante 65s. Reparte los clientes entre los tres
 * ServerNode, ejecuta mensajería privada, mensajería grupal y operaciones sobre
 * GROUP_REGISTRY. Si se derriba un nodo durante la corrida, los clientes afectados
 * intentan reconectarse a otro nodo vivo para medir recuperación del servicio.
 */
public class LoadGenerator {

    private static final List<VirtualClient.Endpoint> NODES = List.of(
            new VirtualClient.Endpoint("localhost", 5001),
            new VirtualClient.Endpoint("localhost", 5002),
            new VirtualClient.Endpoint("localhost", 5003)
    );

    private static final long PING_TIMEOUT_MS = 5_000;

    public static void main(String[] args) throws InterruptedException {
        int numClients = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int durationSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 65;

        if (numClients < 50) {
            System.out.println("[Aviso] La pauta exige mínimo 50 clientes/hilos simultáneos.");
        }
        if (durationSeconds < 60) {
            System.out.println("[Aviso] La pauta exige mínimo 60 segundos sostenidos.");
        }

        String runId = "run" + System.currentTimeMillis();
        String sharedGroup = "loadtest_group_" + runId;

        MetricsRecorder metrics = new MetricsRecorder();
        List<VirtualClient> clients = new ArrayList<>(numClients);

        for (int i = 0; i < numClients; i++) {
            String userId = String.format("%s_load%03d", runId, i);
            clients.add(new VirtualClient(userId, NODES, i % NODES.size(), metrics));
        }

        ExecutorService loginPool = Executors.newFixedThreadPool(Math.min(numClients, 32));
        List<Future<Boolean>> logins = new ArrayList<>();
        for (VirtualClient c : clients) logins.add(loginPool.submit(c::connectAndLogin));
        int loggedIn = 0;
        for (Future<Boolean> f : logins) {
            try { if (f.get()) loggedIn++; } catch (ExecutionException ignored) {}
        }
        loginPool.shutdown();
        System.out.printf("[LoadGenerator] %d/%d clientes autenticados.%n", loggedIn, numClients);
        if (loggedIn < numClients) {
            System.out.println("[Aviso] No todos los clientes autenticaron. Revisa que node1/node2/node3 estén arriba.");
        }

        // Buddies en anillo. Después de una caída, cada VirtualClient puede reconectar a otro nodo.
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).setBuddy(clients.get((i + 1) % clients.size()).getClientId());
        }

        // Setup: grupo compartido único por corrida.
        clients.get(0).doCreateGroup(sharedGroup);
        ExecutorService joinPool = Executors.newFixedThreadPool(Math.min(numClients, 32));
        for (int i = 1; i < clients.size(); i++) {
            VirtualClient c = clients.get(i);
            joinPool.submit(() -> c.doJoinGroup(sharedGroup));
        }
        joinPool.shutdown();
        joinPool.awaitTermination(30, TimeUnit.SECONDS);

        Thread failureListener = new Thread(() -> {
            System.out.println(">>> Presiona ENTER justo después de derribar el nodo/coordinador <<<");
            new java.util.Scanner(System.in).nextLine();
            metrics.markFailureInjected();
        }, "failure-trigger");
        failureListener.setDaemon(true);
        failureListener.start();

        ExecutorService loadPool = Executors.newFixedThreadPool(numClients);
        metrics.start();
        long endAt = System.currentTimeMillis() + durationSeconds * 1000L;

        for (VirtualClient c : clients) loadPool.submit(() -> runClientLoop(c, sharedGroup, endAt));

        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
        janitor.scheduleAtFixedRate(() -> clients.forEach(c -> c.purgeStalePings(PING_TIMEOUT_MS)),
                2, 2, TimeUnit.SECONDS);

        loadPool.shutdown();
        loadPool.awaitTermination(durationSeconds + 30L, TimeUnit.SECONDS);
        janitor.shutdownNow();
        metrics.stop();

        // Purga final de pings pendientes antes de calcular el reporte.
        clients.forEach(c -> c.purgeStalePings(0));

        for (VirtualClient c : clients) c.disconnect();
        metrics.finalizeAndReport(Path.of("loadtest-results", "loadtest_" + System.currentTimeMillis() + ".csv"));

        System.out.println("\nSiguiente paso:");
        System.out.println("  java -cp target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar whatsapp.loadtest.CoordinationLogAnalyzer logs/events-node1.log logs/events-node2.log logs/events-node3.log");
    }

    private static void runClientLoop(VirtualClient c, String sharedGroup, long endAt) {
        Random random = new Random();
        int localGroupSeq = 0;
        while (System.currentTimeMillis() < endAt) {
            double r = random.nextDouble();
            if (r < 0.45) {
                c.doPrivatePing();
            } else if (r < 0.95) {
                c.doGroupMessage(sharedGroup);
            } else {
                c.doCreateGroup("lt_" + c.getClientId() + "_" + (localGroupSeq++));
            }
            try {
                // Menos agresivo que la versión anterior: evita falsos DOWN por saturar los pools/heartbeats.
                Thread.sleep(25 + random.nextInt(50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
