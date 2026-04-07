
package whatsapp.server.core;

import whatsapp.server.handlers.ManejadorCliente;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
Este código establece un servidor multi-hilo (un hilo por cliente) y prepara 
el terreno para la sincronización de recursos y el manejo de fallos independientes.
*/

public class ServidorPrincipal {
    private static final int PUERTO = 5000;
    
    // Lista sincronizada para proteger los recursos compartidos desde el inicio
    public static final List<ManejadorCliente> clientesConectados = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        System.out.println("=== Iniciando Servidor Central de WhatsApp ===");
        
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("Servidor escuchando en el puerto " + PUERTO + "...\n");
            
            while (true) {
                // Aceptación de clientes por sockets
                Socket socketCliente = serverSocket.accept();
                System.out.println("[Servidor] Nueva conexión entrante desde: " + socketCliente.getInetAddress());
                
                // Delega la conexión a un hilo independiente
                ManejadorCliente manejador = new ManejadorCliente(socketCliente);
                clientesConectados.add(manejador);
                manejador.start();
            }
        } catch (IOException e) {
            System.err.println("[Error Crítico] Fallo en el socket principal: " + e.getMessage());
        }
    }
}
