package whatsapp.server.managers;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administra la vista local del registro distribuido de grupos.
 *
 * <p>Esta clase mantiene grupos y membresías desde la perspectiva de un nodo.
 * No implementa por sí sola coordinación distribuida. Las operaciones críticas
 * sobre grupos deberán ser protegidas posteriormente mediante Ricart-Agrawala
 * sobre el recurso lógico {@code GROUP_REGISTRY}.</p>
 */
public class DistributedGroupManager {

    private final ConcurrentHashMap<String, Set<String>> groupMembers = new ConcurrentHashMap<>();

    /**
     * Crea un grupo con una lista inicial de miembros.
     *
     * @param groupId identificador del grupo
     * @param initialMembers miembros iniciales
     * @return {@code true} si el grupo fue creado; {@code false} si ya existía
     */
    public boolean createGroup(String groupId, Set<String> initialMembers) {
        if (groupId == null || groupId.isBlank()) {
            return false;
        }

        Set<String> safeMembers = initialMembers == null
                ? ConcurrentHashMap.newKeySet()
                : ConcurrentHashMap.newKeySet(initialMembers.size());

        if (initialMembers != null) {
            safeMembers.addAll(initialMembers);
        }

        return groupMembers.putIfAbsent(groupId, safeMembers) == null;
    }

    /**
     * Agrega un miembro a un grupo.
     *
     * @param groupId identificador del grupo
     * @param userId usuario a agregar
     * @return {@code true} si el usuario fue agregado
     */
    public boolean addMember(String groupId, String userId) {
        if (groupId == null || userId == null) {
            return false;
        }

        groupMembers.putIfAbsent(groupId, ConcurrentHashMap.newKeySet());
        return groupMembers.get(groupId).add(userId);
    }

    /**
     * Indica si existe un grupo.
     *
     * @param groupId identificador del grupo
     * @return {@code true} si el grupo existe
     */
    public boolean groupExists(String groupId) {
        return groupMembers.containsKey(groupId);
    }

    /**
     * Indica si un usuario pertenece a un grupo.
     *
     * @param groupId identificador del grupo
     * @param userId identificador del usuario
     * @return {@code true} si el usuario pertenece al grupo
     */
    public boolean isMember(String groupId, String userId) {
        Set<String> members = groupMembers.get(groupId);
        return members != null && members.contains(userId);
    }

    /**
     * Obtiene una copia segura de los miembros de un grupo.
     *
     * @param groupId identificador del grupo
     * @return copia de los miembros actuales
     */
    public Set<String> getMembersSnapshot(String groupId) {
        Set<String> members = groupMembers.get(groupId);

        if (members == null) {
            return Collections.emptySet();
        }

        return new HashSet<>(members);
    }
}