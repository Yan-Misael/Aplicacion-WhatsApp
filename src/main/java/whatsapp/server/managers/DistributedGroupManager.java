package whatsapp.server.managers;

import whatsapp.server.mutex.RicartAgrawalaCoordinator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administra la vista local del registro distribuido de grupos.
 *
 * <p>Las operaciones de escritura ({@link #createGroup} y {@link #addMember})
 * están protegidas por el algoritmo de Ricart-Agrawala cuando se inyecta un
 * {@link RicartAgrawalaCoordinator}. Sin coordinador se comporta como un mapa
 * concurrente local (para compatibilidad con la entrega previa).</p>
 *
 * <p>Las operaciones de lectura no requieren mutex distribuido; la consistencia
 * eventual entre nodos se logra mediante {@code GROUP_UPDATE_COMMIT} (Persona 3).</p>
 */
public class DistributedGroupManager {

    private static final String GROUP_REGISTRY = "GROUP_REGISTRY";

    private final ConcurrentHashMap<String, Set<String>> groupMembers = new ConcurrentHashMap<>();

    /** Inyectado tras construcción para evitar dependencia circular. */
    private volatile RicartAgrawalaCoordinator ricartCoordinator;

    /**
     * Inyecta el coordinador de Ricart-Agrawala.
     * Debe llamarse ANTES de que operen los clientes.
     */
    public void setRicartCoordinator(RicartAgrawalaCoordinator coordinator) {
        this.ricartCoordinator = coordinator;
    }

    /**
     * Crea un grupo con una lista inicial de miembros.
     * Si hay coordinador R-A, la operación es mutuamente exclusiva en todo el clúster.
     *
     * @return {@code true} si el grupo fue creado; {@code false} si ya existía
     */
    public boolean createGroup(String groupId, Set<String> initialMembers) {
        if (groupId == null || groupId.isBlank()) return false;

        if (ricartCoordinator != null) {
            boolean[] result = {false};
            ricartCoordinator.executeCriticalSection(GROUP_REGISTRY,
                    () -> result[0] = doCreateGroup(groupId, initialMembers));
            return result[0];
        }
        return doCreateGroup(groupId, initialMembers);
    }

    /**
     * Agrega un miembro a un grupo.
     * Si hay coordinador R-A, la operación es mutuamente exclusiva en todo el clúster.
     *
     * @return {@code true} si el usuario fue agregado
     */
    public boolean addMember(String groupId, String userId) {
        if (groupId == null || userId == null) return false;

        if (ricartCoordinator != null) {
            boolean[] result = {false};
            ricartCoordinator.executeCriticalSection(GROUP_REGISTRY,
                    () -> result[0] = doAddMember(groupId, userId));
            return result[0];
        }
        return doAddMember(groupId, userId);
    }

    public boolean groupExists(String groupId) {
        return groupMembers.containsKey(groupId);
    }

    public boolean isMember(String groupId, String userId) {
        Set<String> members = groupMembers.get(groupId);
        return members != null && members.contains(userId);
    }

    public Set<String> getMembersSnapshot(String groupId) {
        Set<String> members = groupMembers.get(groupId);
        return members == null ? Collections.emptySet() : new HashSet<>(members);
    }

    // -------------------------------------------------------------------------
    // Operaciones internas (sin bloqueo distribuido)
    // -------------------------------------------------------------------------

    private boolean doCreateGroup(String groupId, Set<String> initialMembers) {
        Set<String> safeMembers = ConcurrentHashMap.newKeySet();
        if (initialMembers != null) safeMembers.addAll(initialMembers);
        return groupMembers.putIfAbsent(groupId, safeMembers) == null;
    }

    private boolean doAddMember(String groupId, String userId) {
        groupMembers.putIfAbsent(groupId, ConcurrentHashMap.newKeySet());
        return groupMembers.get(groupId).add(userId);
    }
}
