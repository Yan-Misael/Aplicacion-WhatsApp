package whatsapp.loadtest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Generador de carga real (Sección 3 de la pauta ICI-4344).
 *
 * Uso: java whatsapp.loadtest.LoadGenerator [numClientes] [duracionSegundos]
 * Por defecto: 60 clientes, 70s (sobre los mínimos 50/60s de 3.1, con margen
 * de ramp-up/ramp-down).
 *
 * Reparte los clientes round-robin entre los 3 nodos (puertos 5001/5002/5003,
 * deben coincidir con config/node1.properties..node3.properties) para que la
 * carga sea real multinodo.
 *
 * Mezcla de operaciones por iteración (exige las dos funciones principales y,
 * en particular, GROUP_REGISTRY vía Ricart-Agrawala):
 *   45% PRIVATE_PING   -> mensajería privada + cruce inter-nodo
 *   35% GROUP_MESSAGE  -> mensajería grupal
 *   20% CREATE_GROUP   -> fuerza una ronda real de Ricart-Agrawala sobre GROUP_REGISTRY
 *
 * Falla inducida (Sección 3.3): el operador presiona ENTER en el instante en que
 * derriba el nodo/coordinador; eso marca el timestamp que MetricsRecorder usa
 * para partir el reporte en las 3 ventanas de la Tabla de resultados del informe.
 */
public class LoadGenerator {

    private record NodeTarget(String host, int port) {}

    private static final NodeTarget[] NODES = {
            new NodeTarget("localhost", 5001),
            new NodeTarget("localhost", 5002),
            new NodeTarget("localhost", 5003)
    };

    private static final String SHARED_GROUP = "loadtest_group";
    private static final long PING_TIMEOUT_MS = 8_000;

    public static void main(String[] args) throws InterruptedException {
        int numClients = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        int durationSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 70;

        if (numClients < 50) {
            System.out.println("[Aviso] La pauta (3.1) exige mínimo 50 clientes/hilos simultáneos.");
        }

        MetricsRecorder metrics = new MetricsRecorder();
        List<VirtualClient> clients = new ArrayList<>(numClients);

        // 1) Conectar y loguear a todos los clientes, repartidos round-robin entre nodos.
        for (int i = 0; i < numClients; i++) {
            NodeTarget target = NODES[i % NODES.length];
            clients.add(new VirtualClient(String.format("load%03d", i), target.host(), target.port(), metrics));
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

        // 2) Buddies en anillo (cruza nodos estadísticamente) + grupo compartido.
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).setBuddy(clients.get((i + 1) % clients.size()).getClientId());
        }
        clients.get(0).doCreateGroup(SHARED_GROUP);
        ExecutorService joinPool = Executors.newFixedThreadPool(Math.min(numClients, 32));
        for (int i = 1; i < clients.size(); i++) {
            VirtualClient c = clients.get(i);
            joinPool.submit(() -> c.doJoinGroup(SHARED_GROUP));
        }
        joinPool.shutdown();
        joinPool.awaitTermination(30, TimeUnit.SECONDS);

        // 3) Hilo que escucha ENTER para marcar la falla inducida (Sección 3.3).
        Thread failureListener = new Thread(() -> {
            System.out.println(">>> Presiona ENTER en el instante exacto en que derribes el nodo/coordinador <<<");
            new java.util.Scanner(System.in).nextLine();
            metrics.markFailureInjected();
        }, "failure-trigger");
        failureListener.setDaemon(true);
        failureListener.start();

        // 4) Carga sostenida: cada cliente, en su propio hilo, ejecuta operaciones en
        //    bucle durante durationSeconds (Sección 3.1: >=50 hilos, >=60s).
        ExecutorService loadPool = Executors.newFixedThreadPool(numClients);
        metrics.start();
        long endAt = System.currentTimeMillis() + durationSeconds * 1000L;

        for (VirtualClient c : clients) loadPool.submit(() -> runClientLoop(c, endAt));

        // Limpia PINGs sin respuesta (clientes cuyo buddy murió con el nodo derribado).
        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
        janitor.scheduleAtFixedRate(() -> clients.forEach(c -> c.purgeStalePings(PING_TIMEOUT_MS)),
                5, 5, TimeUnit.SECONDS);

        loadPool.shutdown();
        loadPool.awaitTermination(durationSeconds + 30, TimeUnit.SECONDS);
        janitor.shutdownNow();
        metrics.stop();

        // 5) Cierre ordenado y reporte final.
        for (VirtualClient c : clients) c.disconnect();
        metrics.finalizeAndReport(Path.of("loadtest-results", "loadtest_" + System.currentTimeMillis() + ".csv"));

        System.out.println("\nCorre CoordinationLogAnalyzer sobre n1.log/n2.log/n3.log de esta misma corrida");
        System.out.println("para completar la columna 'Mensajes de coordinación' de la Tabla de resultados.");
    }

    private static void runClientLoop(VirtualClient c, long endAt) {
        Random random = new Random();
        int localGroupSeq = 0;
        while (System.currentTimeMillis() < endAt) {
            double r = random.nextDouble();
            if (r < 0.45) {
                c.doPrivatePing();
            } else if (r < 0.80) {
                c.doGroupMessage(SHARED_GROUP);
            } else {
                // Grupo único por cliente+iteración: garantiza una ronda REAL de
                // Ricart-Agrawala (un join repetido sería rechazado: "ya es miembro").
                c.doCreateGroup("lt_" + c.getClientId() + "_" + (localGroupSeq++));
            }
            try {
                Thread.sleep(5 + random.nextInt(25)); // jitter chico: evita lockstep, no limita el throughput real
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}