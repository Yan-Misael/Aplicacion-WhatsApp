package whatsapp.server.handlers;

import whatsapp.common.models.PaqueteRed;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.common.models.PaqueteLogin;
import whatsapp.common.models.PaqueteConfirm;
import whatsapp.common.models.PaqueteError;
import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteLogout;
import whatsapp.server.managers.SessionManager;
import whatsapp.server.managers.GroupManager;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;

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
                    procesarPaquete((PaqueteRed) peticion);
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
    private void procesarPaquete(PaqueteRed paquete) throws IOException{
        // Enrutamiento de lógica según el tipo de polimorfismo del mensaje
        // Primero hacer Login
        if (paquete instanceof PaqueteLogin) {
            PaqueteLogin login = (PaqueteLogin) paquete;
            String userId = login.getIdRemitente(); //puede ser el servidors
            boolean registrado = sessionManager.registrarUsuario(userId, this);
            if (registrado) {
                this.idUsuarioAsignado = userId;
                try {
                    enviarObjeto(new PaqueteConfirm(userId, true, "Login con éxito"));
                } catch (IOException e) {
                    liberarRecursos();
                }
                System.out.println("Usuario " + userId + " autenticado");
            } else {
                try {
                    enviarObjeto(new PaqueteError(userId, "El id ya está en uso"));
                } catch (IOException e) {}
                liberarRecursos();
            }
        }
        // Después, si se desea enviar un mensaje
        else if (paquete instanceof PaqueteMensaje) {
            if (idUsuarioAsignado == null) {
                // Lanzamos la excepción hacia arriba, si falla enviar el error, el hilo debe morir.
                enviarObjeto(new PaqueteError("Servidor", "Debe autenticarse antes de enviar mensajes."));
                return;
            }

            PaqueteMensaje msg = (PaqueteMensaje) paquete;

            // Delegación de responsabilidades
            if (msg.isEsGrupo()) {
                procesarMensajeGrupal(msg);
            } else {
                procesarMensajePrivado(msg);
            }
        }
        // Luego, si se desa un servidor
        else if (paquete instanceof PaqueteCrearGrupo) {
            if (idUsuarioAsignado == null) {
                enviarObjeto(new PaqueteError("Servidor", "Debe autenticarse antes de crear grupos."));
                return;
            }
            
            PaqueteCrearGrupo crear = (PaqueteCrearGrupo) paquete;
            groupManager.registrarGrupo(crear.getIdGrupo(), crear.getIdRemitente());
            enviarObjeto(new PaqueteConfirm(idUsuarioAsignado, true, "Grupo '" + crear.getIdGrupo() + "' creado."));
        }
        
        // Finalmente, para el logout
        else if (paquete instanceof PaqueteLogout) {
            System.out.println("Usuario " + idUsuarioAsignado + " cerró sesión.");
            liberarRecursos();
        }
        
        else {
            enviarObjeto(new PaqueteError(idUsuarioAsignado != null ? idUsuarioAsignado : "Servidor", "Paquete no soportado por el servidor."));
        }
    }
    
    /**
     * Envía el objeto (serializado) de vuelta a la red.
     */
    public void enviarObjeto(PaqueteRed paquete) throws IOException {
        out.writeObject(paquete);
        out.flush();
    }
    
    /**
     * Enruta un mensaje a un destinatario específico.
     * Falla controladamente si el usuario no existe.
     */
    private void procesarMensajePrivado(PaqueteMensaje msg) throws IOException {
        ManejadorCliente destinatario = sessionManager.obtenerSesion(msg.getIdDestinatario());

        if (destinatario == null) {
            enviarObjeto(new PaqueteError("Servidor", "El usuario '" + msg.getIdDestinatario() + "' no está conectado."));
            return;
        }

        try {
            destinatario.enviarObjeto(msg);
        } catch (IOException e) {
            // El destinatario cayó justo en el momento del envío.
            System.err.println("Fallo al entregar mensaje a " + msg.getIdDestinatario());
            enviarObjeto(new PaqueteError("Servidor", "El mensaje no pudo ser entregado. '" + msg.getIdDestinatario() + "' perdió conexión."));
        }
    }

    /**
     * 1.Realiza un broadcast del mensaje a todos los miembros del grupo, excluyendo al remitente.
     * 2.Aisla los fallos individuales para no interrumpir el envío a los demás miembros.
     */
    private void procesarMensajeGrupal(PaqueteMensaje msg) {
        List<String> miembros = groupManager.obtenerCopiaMiembros(msg.getIdDestinatario());

        for (String idMiembro : miembros) {
            // No enviar el mensaje de vuelta al que lo emitió
            if (idMiembro.equals(msg.getIdRemitente())) {
                continue;
            }

            ManejadorCliente manejadorDestino = sessionManager.obtenerSesion(idMiembro);

            if (manejadorDestino != null) {
                try {
                    manejadorDestino.enviarObjeto(msg);
                } catch (IOException e) {
                    // Si un miembro del grupo tiene el socket roto, registramos el fallo,
                    // pero continuamos el ciclo para que los demás sí reciban el mensaje.
                    System.err.println("Error aislando nodo caído en grupo: " + idMiembro);
                }
            }
        }
    }
    
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