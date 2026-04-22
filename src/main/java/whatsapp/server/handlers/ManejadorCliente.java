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
    private void procesarPaquete(PaqueteRed paquete) {
        // Enrutamiento de lógica según el tipo de polimorfismo del mensaje
        // primero login
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
                System.out.println("Usuario " + userId + "autenticado");
            } else {
                try {
                    enviarObjeto(new PaqueteError(userId, "El id ya está en uso"));
                } catch (IOException e) {}
                liberarRecursos();
            }
        }
        
        else if (paquete instanceof PaqueteMensaje) {
            if (idUsuarioAsignado == null) {
                try {
                    enviarObjeto(new PaqueteError("desconocido", "debe autenticarse"));
                } catch (IOException e) {}
                return;
            }
            PaqueteMensaje msg = (PaqueteMensaje) paquete;
            if(msg.isEsGrupo()) {
                List<String> miembros = groupManager.obtenerCopiaMiembros(msg.getIdDestinatario());
                for (String miembro : miembros) {
                    if (miembro.equals(msg.getIdRemitente())) continue;
                    ManejadorCliente manejador = sessionManager.obtenerSesion(miembro);
                    if (manejador != null) {
                        try {
                            manejador.enviarObjeto(msg);
                        } catch (IOException e) {
                            System.err.println("No se pudo enviar msg grupal a " + miembro); //en caso de q falle un miembro :p
                        }
                    }
                }
            } else {
                //aqui va el chat priv
                ManejadorCliente destinatario = sessionManager.obtenerSesion(msg.getIdDestinatario());
                if (destinatario != null) {
                    try {
                        destinatario.enviarObjeto(msg);
                    } catch (IOException e) {
                        try {
                            enviarObjeto(new PaqueteError(idUsuarioAsignado, "El usuario x no recibió el msg"));
                        } catch (IOException ex) {}
                    }
                } else {
                    try {
                        enviarObjeto(new PaqueteError(idUsuarioAsignado, "Usuario " + msg.getIdDestinatario() + " no esta conectado"));
                    } catch (IOException e) {}
                }
            }
        }
        else if (paquete instanceof PaqueteCrearGrupo) {
            if (idUsuarioAsignado == null) {
                try {
                    enviarObjeto(new PaqueteError("desconocido", "no autenticado")); 
                } catch (IOException e) {}
                return;
            }
            PaqueteCrearGrupo crear = (PaqueteCrearGrupo) paquete;
            groupManager.registrarGrupo(crear.getIdGrupo(), crear.getIdRemitente());
            try {
                enviarObjeto(new PaqueteConfirm(idUsuarioAsignado, true, "Grupo " + crear.getIdGrupo() + " creado"));
            } catch (IOException e) {
                liberarRecursos();
            }
        }
        // paquetelogout
        else if (paquete instanceof PaqueteLogout) {
            System.out.println("Usuario " + idUsuarioAsignado + " cerró sesión");
            liberarRecursos();
        }
        // en caso de q no lo reconozca
        else {
            try {
                enviarObjeto(new PaqueteError(idUsuarioAsignado != null ? idUsuarioAsignado : "desconocido", "no soportado"));
            } catch (IOException e) {}
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