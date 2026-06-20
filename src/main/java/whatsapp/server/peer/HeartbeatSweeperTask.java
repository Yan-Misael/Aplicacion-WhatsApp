package whatsapp.server.peer;

import whatsapp.server.membership.MembershipManager;
import whatsapp.server.node.NodeInfo;
import whatsapp.server.node.NodeStatus;

/**
 * Tarea programada (Sweeper) que revisa pasivamente el tiempo de la 
 * última actividad conocida de cada peer. Si supera el umbral, declara 
 * el nodo como caído (DOWN) para iniciar los protocolos de recuperación.
 */
public class HeartbeatSweeperTask implements Runnable {
    private final String selfNodeId;
    private final MembershipManager membershipManager;
    private final long timeoutMs;

    public HeartbeatSweeperTask(String selfNodeId, MembershipManager membershipManager, long timeoutMs) {
        this.selfNodeId = selfNodeId;
        this.membershipManager = membershipManager;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void run() {
        try {
            long currentTime = System.currentTimeMillis();

            // Revisa a todos los nodos, independientemente de su estado actual
            for (NodeInfo target : membershipManager.getAllNodes()) {
                if (target.getNodeId().equals(selfNodeId)) {
                    continue;
                }

                long timeSinceLastSeen = currentTime - target.getLastSeenMillis();

                // Si supera el tiempo de tolerancia y el nodo no estaba marcado como DOWN
                if (timeSinceLastSeen > timeoutMs && target.getStatus() != NodeStatus.DOWN) {
                    System.out.println("\n[" + selfNodeId + "] ALERTA DE FALLO DETECTADA");
                    System.out.println("[" + selfNodeId + "] El nodo " + target.getNodeId() + 
                                       " no responde desde hace " + timeSinceLastSeen + "ms.");
                    
                    // Marca el nodo como caído en la membresía
                    membershipManager.markDown(target.getNodeId());
                    System.out.println("[" + selfNodeId + "] Estado de " + target.getNodeId() + " actualizado a DOWN.");

                    // Aquí es donde lanza el evento para que el sistema se reorganice,
                    // inicie la elección de un nuevo coordinador o redistribuya usuarios.
                    iniciarProtocoloRecuperacion(target.getNodeId());
                }
            }
        } catch (Exception e) {
            // Previene que un error silencioso mate el hilo del ScheduledExecutorService
            System.err.println("[" + selfNodeId + "] Error en el Sweeper de fallos: " + e.getMessage());
        }
    }

    /**
     * Placeholder para el inicio de recuperación efectiva.
     */
    private void iniciarProtocoloRecuperacion(String nodoCaido) {
        // Implementar lógica de recuperación
        System.out.println("[" + selfNodeId + "] (Placeholder) Iniciando recuperación por caída de " + nodoCaido + "...\n");
    }
}