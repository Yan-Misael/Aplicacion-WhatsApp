package whatsapp.server.peer;

import whatsapp.server.election.BullyElectionCoordinator;
import whatsapp.server.membership.MembershipManager;
import whatsapp.server.mutex.RicartAgrawalaCoordinator;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.node.NodeStatus;

/**
 * Tarea programada (Sweeper) que revisa pasivamente el tiempo de la
 * última actividad conocida de cada peer. Si supera el umbral, declara
 * el nodo como caído (DOWN) para iniciar los protocolos de recuperación.
 *
 * <p>Al detectar una caída notifica a los coordinadores de coordinación
 * distribuida para que puedan reaccionar:</p>
 * <ul>
 *   <li>{@link RicartAgrawalaCoordinator#onNodeDown(String)} — desbloquea esperas de mutex.</li>
 *   <li>{@link BullyElectionCoordinator#startElection()} — inicia elección si el coordinador cayó.</li>
 * </ul>
 */
public class HeartbeatSweeperTask implements Runnable {

    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final long timeoutMs;
    private final RicartAgrawalaCoordinator ricartCoordinator;
    private final BullyElectionCoordinator bullyCoordinator;

    public HeartbeatSweeperTask(
            String selfNodeId,
            MembershipManager membershipManager,
            long timeoutMs,
            RicartAgrawalaCoordinator ricartCoordinator,
            BullyElectionCoordinator bullyCoordinator) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.timeoutMs = timeoutMs;
        this.ricartCoordinator = ricartCoordinator;
        this.bullyCoordinator = bullyCoordinator;
    }

    @Override
    public void run() {
        try {
            long currentTime = System.currentTimeMillis();

            for (NodeInfo target : membershipManager.getAllNodes()) {
                if (target.getNodeId().equals(selfNodeId)) {
                    continue;
                }

                long timeSinceLastSeen = currentTime - target.getLastSeenMillis();

                if (timeSinceLastSeen > timeoutMs && target.getStatus() != NodeStatus.DOWN) {
                    System.out.println("\n[" + selfNodeId + "] ALERTA DE FALLO DETECTADA");
                    System.out.println("[" + selfNodeId + "] El nodo " + target.getNodeId()
                            + " no responde desde hace " + timeSinceLastSeen + "ms.");

                    membershipManager.markDown(target.getNodeId());
                    System.out.println("[" + selfNodeId + "] Estado de " + target.getNodeId()
                            + " actualizado a DOWN.");

                    iniciarProtocoloRecuperacion(target.getNodeId());
                }
            }
        } catch (Exception e) {
            System.err.println("[" + selfNodeId + "] Error en el Sweeper de fallos: " + e.getMessage());
        }
    }

    /**
     * Dispara los protocolos de recuperación ante la caída de un nodo.
     *
     * <ol>
     *   <li>Notifica a Ricart-Agrawala para desbloquear esperas de mutex.</li>
     *   <li>Si el nodo caído era el coordinador, inicia una nueva elección Bully.</li>
     * </ol>
     */
    private void iniciarProtocoloRecuperacion(String nodoCaido) {
        // 1. Ricart-Agrawala: desbloquear si esperábamos reply de este nodo
        ricartCoordinator.onNodeDown(nodoCaido);

        // 2. Bully: si el coordinador cayó, iniciar nueva elección
        String coordinadorActual = bullyCoordinator.getCurrentCoordinator();
        if (nodoCaido.equals(coordinadorActual)) {
            System.out.println("[" + selfNodeId + "] El coordinador " + nodoCaido
                    + " cayó. Iniciando elección Bully...");
            bullyCoordinator.startElection();
        }
    }
}
