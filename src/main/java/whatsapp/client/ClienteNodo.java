package whatsapp.client;

import whatsapp.common.models.PaqueteConfirm;
import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteUnirseGrupo;
import whatsapp.common.models.PaqueteError;
import whatsapp.common.models.PaqueteLogin;
import whatsapp.common.models.PaqueteMensaje;
import whatsapp.common.models.PaqueteRed;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Scanner;

/**
 * Implementación del nodo cliente para ejecución en terminal del IDE.
 * Se ´presenta la separación de I/O de usuario (hilo principal) de la del I/O de red.
 */
public class ClienteNodo {
    private static final String HOST = "localhost";
    private static final int PUERTO = 5000;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String miId;

    public static void main(String[] args) {
        new ClienteNodo().iniciar();
    }

    public void iniciar() {
        try {
            socket = new Socket(HOST, PUERTO);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            System.out.println("=== Conectado al Servidor Central ===");
            
            // Hilo 1: Escucha activa de red (Asíncrono)
            Thread listenerThread = new Thread(this::escucharServidor);
            listenerThread.setDaemon(true);
            listenerThread.start();

            // Hilo 2: Lectura de comandos del usuario (Bloqueante)
            Scanner scanner = new Scanner(System.in);
            procesarComandos(scanner);

        } catch (IOException e) {
            System.err.println("Fallo de conexión: El servidor no está disponible.");
        }
    }

    private void procesarComandos(Scanner scanner) {
        System.out.println("Comandos disponibles:");
        System.out.println("  /login <nombre_usuario>             — Iniciar sesión");
        System.out.println("  /msg <destinatario> <mensaje>       — Mensaje privado");
        System.out.println("  /creargrupo <id_grupo>              — Crear un grupo nuevo");
        System.out.println("  /unirse <id_grupo>                  — Unirse a un grupo existente");
        System.out.println("  /gmsg <id_grupo> <mensaje>          — Enviar mensaje al grupo");
        System.out.println("  /salir                              — Desconectarse");

        try {
            while (true) {
                String input = scanner.nextLine();
                if (input == null || input.trim().isEmpty()) continue;

                String[] partes = input.split(" ", 3);
                String comando = partes[0].toLowerCase();

                switch (comando) {
                    case "/login":
                        if (partes.length == 2) {
                            miId = partes[1];
                            out.writeObject(new PaqueteLogin(miId));
                            out.flush();
                        } else {
                            System.out.println("Sintaxis: /login <id>");
                        }
                        break;

                    case "/msg":
                        if (partes.length == 3 && miId != null) {
                            String destino = partes[1];
                            String texto = partes[2];
                            out.writeObject(new PaqueteMensaje(miId, destino, texto, false));
                            out.flush();
                        } else {
                            System.out.println("Sintaxis: /msg <destino> <texto>  (Debe hacer login primero)");
                        }
                        break;

                    // ── Comandos grupales (Leiva) ────────────────────────────────────

                    case "/creargrupo":
                        // Crea el grupo en el servidor y agrega al creador como primer miembro
                        if (partes.length == 2 && miId != null) {
                            String idGrupo = partes[1];
                            out.writeObject(new PaqueteCrearGrupo(miId, idGrupo));
                            out.flush();
                        } else {
                            System.out.println("Sintaxis: /creargrupo <id_grupo>  (Debe hacer login primero)");
                        }
                        break;

                    case "/unirse":
                        // Solicita al servidor unirse a un grupo ya existente
                        if (partes.length == 2 && miId != null) {
                            String idGrupo = partes[1];
                            out.writeObject(new PaqueteUnirseGrupo(miId, idGrupo));
                            out.flush();
                        } else {
                            System.out.println("Sintaxis: /unirse <id_grupo>  (Debe hacer login primero)");
                        }
                        break;

                    case "/gmsg":
                        // Envía un mensaje a todos los miembros del grupo (broadcast)
                        if (partes.length == 3 && miId != null) {
                            String idGrupo = partes[1];
                            String texto   = partes[2];
                            // esGrupo = true indica al servidor que enrute a GroupManager
                            out.writeObject(new PaqueteMensaje(miId, idGrupo, texto, true));
                            out.flush();
                        } else {
                            System.out.println("Sintaxis: /gmsg <id_grupo> <texto>  (Debe hacer login primero)");
                        }
                        break;

                    // ── Fin comandos grupales ────────────────────────────────────────

                    case "/salir":
                        System.out.println("Cerrando cliente...");
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Comando no reconocido. Escribe /login, /msg, /creargrupo, /unirse, /gmsg o /salir.");
                }
            }
        } catch (IOException e) {
            System.err.println("Error al enviar datos al servidor.");
        }
    }

    private void escucharServidor() {
        try {
            while (true) {
                Object respuesta = in.readObject();

                if (respuesta instanceof PaqueteMensaje) {
                    PaqueteMensaje msj = (PaqueteMensaje) respuesta;
                    if (msj.isEsGrupo()) {
                        // Mensaje grupal: indica el grupo de origen
                        System.out.println("\n[Grupo " + msj.getIdDestinatario() + "] "
                                + msj.getIdRemitente() + ": " + msj.getContenido());
                    } else {
                        // Mensaje privado
                        System.out.println("\n[" + msj.getIdRemitente() + "]: " + msj.getContenido());
                    }
                }
                else if (respuesta instanceof PaqueteConfirm) {
                    PaqueteConfirm conf = (PaqueteConfirm) respuesta;
                    System.out.println("\n[Sistema]: " + conf.getMensaje());
                }
                else if (respuesta instanceof PaqueteError) {
                    PaqueteError err = (PaqueteError) respuesta;
                    System.err.println("\n[Error]: " + err.getRazon());
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("\n[Sistema] Desconectado del servidor.");
            System.exit(0);
        }
    }
}