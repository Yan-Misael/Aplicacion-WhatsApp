package whatsapp.server.handlers;

import whatsapp.common.models.PaqueteRed;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.server.managers.SessionManager;
import whatsapp.server.managers.GroupManager;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Clse que maneja y comprende la lógica interna de conexión del cliente.
 * Contiene el canal I/O con un cliente específico. Al estar aislado cada instancia en su propio hilo, 
 * si la lectura del socket se bloquea esperando datos, el resto del servidor sigue intacto.
 */
public class ManejadorCliente extends Thread {
    private final Socket socket;
    
    // Se asignan los managers de sesión y grupo vía Inyección de Dependencias
    private final SessionManager sessionManager;
    private final GroupManager groupManager;
    
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String idUsuarioAsignado;

    public ManejadorCliente(Socket socket, SessionManager sessionManager, GroupManager groupManager) {
        this.socket = socket;
        this.sessionManager = sessionManager;
        this.groupManager = groupManager;
    }
    
    @Override
    public void run() {
        try {
            // Usa Object streams para cumplir con el requisito de Marshalling/Serialización
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Loop de Escucha Activa: escucha, deserializa y enruta
            while (!socket.isClosed()) {
                // Con la siguiente linea transofrmamos de bytes a un objeto estructurado (marshmalling)
                Object peticion = in.readObject();
                
                if (peticion instanceof PaqueteRed) {
                    liberarRecursos();
                }
            }
            
        } catch (IOException | ClassNotFoundException e) {
            // Manejo de fallos independientes: Si un cliente se cae, solo este hilo lo captura
            // previniendo la caída total del sistema ante fallos parciales
            System.out.println("[Alerta] Cliente desconectado abruptamente: " + socket.getInetAddress());
        } finally {
            liberarRecursos();
        }
    }
    
    /**
     * Actúa como un switch de enrutamiento polimórfico.
     * Identifica qué tipo de paquete llegó y llama a la lógica correspondiente para dicho paquete.
     */
    private void procesarPaquete(PaqueteRed paquete) {
        // Enrutamiento de lógica según el tipo de polimorfismo del mensaje
        if (paquete instanceof PaqueteMensaje) {
            PaqueteMensaje msj = (PaqueteMensaje) paquete;
            System.out.println("Enrutando mensaje de " + msj.getIdRemitente() + " hacia " + msj.getIdDestinatario());
            // (idk quien seas pero aquí iría lo tuyo b_s)
            // Aquí iría la lógica de utilizar el sessionManager para buscar el socket destino 
            // y hacer outDestino.writeObject(msj);
        }
    }
    
    /**
     * Envía el objeto (serializado) de vuelta a la red.
     */
    public void enviarObjeto(PaqueteRed paquete) throws IOException {
        out.writeObject(paquete);
        out.flush();
    }

    /** deprecado
    private void desconectarLimpiamente() {
        ServidorPrincipal.clientesConectados.remove(this);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el socket: " + e.getMessage());
        }
    }**/
    
    /**
     * Se desconceta al usuario y cierra conexiones para erita fugas de memoria.
     */
    private void liberarRecursos() {
        if (idUsuarioAsignado != null) {
            sessionManager.removerUsuario(idUsuarioAsignado);
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}