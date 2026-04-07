
package whatsapp.server.handlers;

import whatsapp.server.core.ServidorPrincipal;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ManejadorCliente extends Thread {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    public ManejadorCliente(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            // Usa Object streams para cumplir con el requisito de Marshalling/Serialización
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Bucle principal de escucha para este cliente
            while (true) {
                // Aquí el servidor esperará recibir objetos (ej. solicitudes de grupo o llamadas)
                // Object paqueteRecibido = in.readObject();
                // procesarPaquete(paqueteRecibido);
            }
            
        } catch (IOException e) {
            // Manejo de fallos independientes: Si un cliente se cae, solo este hilo lo captura
            // previniendo la caída total del sistema ante fallos parciales
            System.out.println("[Alerta] Cliente desconectado abruptamente: " + socket.getInetAddress());
        } finally {
            desconectarLimpiamente();
        }
    }

    private void desconectarLimpiamente() {
        ServidorPrincipal.clientesConectados.remove(this);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el socket: " + e.getMessage());
        }
    }
}