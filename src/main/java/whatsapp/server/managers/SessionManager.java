package whatsapp.server.managers;

import whatsapp.server.handlers.ManejadorCliente;
import java.util.HashMap;
import java.util.Map;


/**
 * [Singleton]
 * Gestor de estado concurrente para las sesiones activas del servidor.
 * <p>
 * Centraliza y protege el acceso al mapa de enrutamiento de clientes. Emplea un
 * Controlador explícito (Object lock) para garantizar la exclusión mutua en la región crítica,
 * previniendo condiciones de carrera durante el registro de conexiones simultáneas.
 * </p>
 * * @author Ignacio Reyes
 * @version 1.0
 */
public class SessionManager {
    /**
     * Estructura de datos en la que se almacenan las sesiones activas cuyo acceso
     * tiene que ser controlado por la variable de control monitorLock.
     */
    private final Map<String, ManejadorCliente> sesionesActivas;
    /**
     * Lock de sincronización de tipo exclusivo. Se utiliza para evitar la exposición
     * del monitor de la instancia (this), previniendo bloqueos externos accidentales
     * o interbloqueos (deadlocks) si otra clase intenta sincronizar sobre esta instancia.
     */
    private final Object monitorLock = new Object();

    /**
     * Constructor qe inicializa el gestor de sesiones con un mapa vacío.
     */
    public SessionManager() {
        this.sesionesActivas = new HashMap<>();
    }

    /**
     * Intenta registrar un nuevo usuario en el sistema de forma atómica.
     * * @param idUsuario Identificador único del cliente que solicita conexión.
     * @param manejador Instancia del hilo de red (ManejadorCliente) asociado al usuario.
     * @return {@code true} si el usuario fue registrado con éxito; {@code false} si 
     * ya existía una sesión activa con ese identificador.
     */
    public boolean registrarUsuario(String idUsuario, ManejadorCliente manejador) {
        synchronized (monitorLock) {
            if (sesionesActivas.containsKey(idUsuario)) {
                return false; 
            }
            sesionesActivas.put(idUsuario, manejador);
            return true;
        }
    }

    /**
     * Libera de forma segura la sesión de un usuario y elimina su entrada del 
     * mapa de enrutamiento.
     * * @param idUsuario Identificador del usuario a desconectar.
     */
    public void removerUsuario(String idUsuario) {
        if (idUsuario == null) {
            return;
        }
        
        synchronized (monitorLock) {
            sesionesActivas.remove(idUsuario);
        }
    }

    /**
     * Recupera la instancia del manejador de red asociado a un usuario específico.
     * La lectura se realiza dentro del bloque sincronizado para garantizar la 
     * visibilidad en memoria frente a escrituras recientes de otros hilos.
     * * @param idUsuario Identificador del destinatario.
     * @return El {@link ManejadorCliente} asociado, o {@code null} si el usuario 
     * no está conectado.
     */
    public ManejadorCliente obtenerSesion(String idUsuario) {
        synchronized (monitorLock) {
            return sesionesActivas.get(idUsuario);
        }
    }
}