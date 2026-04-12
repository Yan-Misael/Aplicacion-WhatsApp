package whatsapp.server.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * [Singleton]
 * Gestor transaccional para la administración y enrutamiento de salas de chat grupales.
 * <p>
 * Implementa un mecanismo de segmentación de bloqueos (Read-Write Lock) para optimizar 
 * el rendimiento bajo operaciones de lectura intensiva (broadcasting). Múltiples hilos 
 * pueden leer la lista de miembros de un grupo simultáneamente, pero la adición de 
 * miembros requiere acceso exclusivo, paralizando temporalmente las lecturas para 
 * mantener la coherencia del estado.
 * </p>
 * * @author Nacho
 * @version 1.0
 */
public class GroupManager {
    private final Map<String, List<String>> gruposActivos;
    private final ReadWriteLock rwLock; // API de Locks exigida

    /**
     * Constructor que inicializa el gestor instanciando el mapa de grupos y el locks de lectura/escritura.
     */
    public GroupManager() {
        this.gruposActivos = new HashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
    }

    /**
     * Crea un grupo nuevo y añade al fundador como primer miembro, asegurando que no haya colisiones de ID.
     * * @param idGrupo   Identificador único del grupo a crear.
     * @param idCreador Identificador del usuario que funda el grupo.
     */
    public void registrarGrupo(String idGrupo, String idCreador) {
        rwLock.writeLock().lock();
        try {
            if (!gruposActivos.containsKey(idGrupo)) {
                List<String> miembros = new ArrayList<>();
                miembros.add(idCreador);
                gruposActivos.put(idGrupo, miembros);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Devuelve los integrantes actuales de un grupo. 
     * Se entrega un clon de la lista para que el hilo que la reciba pueda iterarla 
     * a su propio ritmo sin bloquear la sala ni arriesgar caídas del sistema (profilaxis de concurrencia).
     * * @param idGrupo ID del grupo a consultar.
     * @return Una lista nueva con los participantes, o una lista vacía si no existe.
     */
    public List<String> obtenerCopiaMiembros(String idGrupo) {
        rwLock.readLock().lock();
        try {
            List<String> miembros = gruposActivos.get(idGrupo);
            
            // Si pasamos la lista original, quien llame a este método podría
            // recorrerla mientras otro hilo mete o saca a alguien del grupo. 
            return ( miembros != null ? new ArrayList<>(miembros) : new ArrayList<>() );
        } finally {
            rwLock.readLock().unlock();
        }
    }
}