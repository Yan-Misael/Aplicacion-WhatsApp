package whatsapp.server.directory;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Directorio distribuido lógico que asocia usuarios con nodos.
 *
 * <p>En la Entrega Inicial, el servidor central sabía localmente qué usuarios
 * estaban conectados. En la arquitectura multiservidor, cada nodo necesita saber
 * en qué nodo se encuentra un usuario para poder enrutar mensajes remotos.</p>
 *
 * <p>Esta clase representa la vista local del directorio global usuario -> nodo.
 * La información se actualiza localmente durante login/logout y se replica entre
 * nodos mediante mensajes como USER_LOGIN_ANNOUNCE y USER_LOGOUT_ANNOUNCE.</p>
 *
 * <p>Además, cuando un nodo es marcado como DOWN, se eliminan del directorio
 * todos los usuarios que estaban asociados a ese nodo, evitando que MessageRouter
 * siga intentando enrutar mensajes hacia un peer caído.</p>
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
        if (userId != null && !userId.isBlank()) {
            userToNode.remove(userId);
        }
    }

    /**
     * Elimina todas las ubicaciones de usuarios asociadas a un nodo.
     *
     * <p>Este método debe invocarse cuando MembershipManager o HeartbeatSweeperTask
     * detectan que un nodo pasó a estado DOWN. Así se evita que el router siga
     * intentando enviar mensajes privados o grupales hacia usuarios que estaban
     * conectados al nodo caído.</p>
     *
     * @param nodeId identificador del nodo caído
     * @return cantidad de usuarios removidos del directorio
     */
    public int removeUsersByNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return 0;
        }

        int before = userToNode.size();

        userToNode.entrySet().removeIf(entry -> nodeId.equals(entry.getValue()));

        return before - userToNode.size();
    }

    /**
     * Retorna los usuarios actualmente asociados a un nodo.
     *
     * @param nodeId identificador del nodo
     * @return conjunto de usuarios registrados en ese nodo
     */
    public Set<String> getUsersByNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return Collections.emptySet();
        }

        return userToNode.entrySet()
                .stream()
                .filter(entry -> nodeId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Busca el nodo donde se encuentra un usuario.
     *
     * @param userId identificador del usuario
     * @return nodo donde está conectado, si se conoce
     */
    public Optional<String> findNodeForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(userToNode.get(userId));
    }

    /**
     * Indica si existe una ubicación conocida para el usuario.
     *
     * @param userId identificador del usuario
     * @return {@code true} si el usuario está registrado en el directorio
     */
    public boolean isUserKnown(String userId) {
        return userId != null && !userId.isBlank() && userToNode.containsKey(userId);
    }

    /**
     * Entrega una copia inmutable del directorio actual para depuración, logs o pruebas.
     *
     * @return snapshot usuario -> nodo
     */
    public Map<String, String> snapshot() {
        return Map.copyOf(userToNode);
    }
}