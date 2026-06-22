package whatsapp.server.managers;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administra las sesiones conectadas localmente a un {@code ServerNode}.
 *
 * <p>A diferencia del {@code SessionManager} centralizado de la Entrega Inicial,
 * este administrador solo debe conocer usuarios conectados al nodo actual.</p>
 *
 * @param <T> tipo del handler o recurso asociado a una sesión local
 */
public class LocalSessionManager<T> {

    private final ConcurrentHashMap<String, T> localSessions = new ConcurrentHashMap<>();

    /**
     * Registra una sesión local.
     *
     * @param userId identificador del usuario
     * @param handler handler o recurso asociado a la conexión local
     * @return {@code true} si se registró correctamente; {@code false} si ya existía
     */
    public boolean registerLocalSession(String userId, T handler) {
        if (userId == null || userId.isBlank() || handler == null) {
            return false;
        }

        return localSessions.putIfAbsent(userId, handler) == null;
    }

    /**
     * Obtiene una sesión local.
     *
     * @param userId identificador del usuario
     * @return sesión local, si existe
     */
    public Optional<T> getLocalSession(String userId) {
        return Optional.ofNullable(localSessions.get(userId));
    }

    /**
     * Elimina una sesión local.
     *
     * @param userId identificador del usuario
     */
    public void removeLocalSession(String userId) {
        if (userId != null) {
            localSessions.remove(userId);
        }
    }

    /**
     * Indica si un usuario está conectado localmente.
     *
     * @param userId identificador del usuario
     * @return {@code true} si el usuario está conectado al nodo actual
     */
    public boolean isLocal(String userId) {
        return localSessions.containsKey(userId);
    }
}