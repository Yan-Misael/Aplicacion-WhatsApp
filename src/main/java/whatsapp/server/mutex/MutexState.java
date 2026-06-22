package whatsapp.server.mutex;

/**
 * Estados posibles de un nodo en el algoritmo de Ricart-Agrawala.
 */
public enum MutexState {
    /** Sin interés en la sección crítica. */
    RELEASED,
    /** Esperando permisos de los demás nodos. */
    WANTED,
    /** En posesión de la sección crítica. */
    HELD
}
