package whatsapp.server.core;

import whatsapp.server.handlers.ManejadorCliente;
import whatsapp.server.managers.SessionManager;
import whatsapp.server.managers.GroupManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
/**
 * Este código establece un servidor multi-hilo (un hilo por cliente) y prepara 
 * el terreno para la sincronización de recursos y el manejo de fallos independientes.
 * Además, por diseño, es en el servidor el único lugar donde se inicialian los gestores de estado o 
 * managers, recordando que son Singletons. También se configura la topología de la red.
*/
public class ServidorPrincipal {
    private static final int PUERTO = 5000;
    
    public static void main(String[] args) {
        System.out.println("=== Iniciando Servidor Central de WhatsApp ===");
        
        // Inicialización de los recursos compartidos. 
        // Solo existe una instancia de estos gestores en todo el sistema, pues son Singletons.
        SessionManager sessionManager = new SessionManager();
        GroupManager groupManager = new GroupManager();
        
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("Servidor escuchando en el puerto " + PUERTO + "...\n");
            
            // El hilo principal se bloquea aquí esperando nuevos clientes
            while (true) {
                // Aceptación de clientes por sockets
                Socket socketCliente = serverSocket.accept();
                System.out.println("[Servidor] Nueva conexión establecida desde: " + socketCliente.getInetAddress());
                
                // Pasamos las referencias de memoria compartida al nuevo hilo
                ManejadorCliente manejador = new ManejadorCliente(socketCliente, sessionManager, groupManager);
                // Delegamos la conexión, posteriormente el hilo principal vuelve a escuchar el puerto inmediatamente
                manejador.start();
            }
        } catch (IOException e) {
            System.err.println("[Error Crítico] Fallo en el socket principal: " + e.getMessage());
        }
    }
}
