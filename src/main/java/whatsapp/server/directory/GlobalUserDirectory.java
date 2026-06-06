package whatsapp.server.directory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directorio distribuido lógico que asocia usuarios con nodos.
 *
 * <p>En la Entrega Inicial, el servidor central sabía localmente qué usuarios
 * estaban conectados. En la arquitectura multiservidor, cada nodo necesita saber
 * en qué nodo se encuentra un usuario para poder enrutar mensajes remotos.</p>
 *
 * <p>Esta clase representa la base local del directorio. La replicación entre nodos
 * será incorporada posteriormente mediante mensajes como
 * {@code USER_LOGIN_ANNOUNCE} y {@code USER_LOGOUT_ANNOUNCE}.</p>
 */
public class GlobalUserDirectory {

    private final ConcurrentHashMap<String, String> userToNode = new ConcurrentHashMap<>();

    /**
     * Registra la ubicación lógica de un usuario.
     *
     * @param userId identificador del usuario
     * @param nodeId nodo donde está conectado el usuario
     */
    public void registerUserLocation(String userId, String nodeId) {
        if (userId == null || userId.isBlank() || nodeId == null || nodeId.isBlank()) {
            return;
        }

        userToNode.put(userId, nodeId);
    }

    /**
     * Elimina la ubicación conocida de un usuario.
     *
     * @param userId identificador del usuario
     */
    public void removeUserLocation(String userId) {
        if (userId != null) {
            userToNode.remove(userId);
        }
    }

    /**
     * Busca el nodo donde se encuentra un usuario.
     *
     * @param userId identificador del usuario
     * @return nodo donde está conectado, si se conoce
     */
    public Optional<String> findNodeForUser(String userId) {
        return Optional.ofNullable(userToNode.get(userId));
    }

    /**
     * Indica si existe una ubicación conocida para el usuario.
     *
     * @param userId identificador del usuario
     * @return {@code true} si el usuario está registrado en el directorio
     */
    public boolean isUserKnown(String userId) {
        return userToNode.containsKey(userId);
    }
}