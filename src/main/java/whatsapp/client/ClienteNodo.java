package whatsapp.client;

import whatsapp.common.models.PaqueteConfirm;
import whatsapp.common.models.PaqueteCrearGrupo;
import whatsapp.common.models.PaqueteUnirseGrupo;
import whatsapp.common.models.PaqueteError;
import whatsapp.common.models.PaqueteLogin;
import whatsapp.common.models.PaqueteLogout;
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
    private static String HOST = "localhost";
    private static final int PUERTO = 2346;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String miId;
    
    private static final String RESET = "\u001B[0m";
    private static final String[] COLORES = {
        "\u001B[31m",
        "\u001B[32m",
        "\u001B[33m",
        "\u001B[34m",
        "\u001B[35m",
        "\u001B[36m",
        "\u001B[41m",
        "\u001B[42m",
        "\u001B[43m",
        "\u001B[44m",
        "\u001B[45m",
        "\u001B[46m",
        "\u001B[91m",
        "\u001B[92m",
        "\u001B[93m",
        "\u001B[94m",
        "\u001B[95m",
        "\u001B[96m",
        "\u001B[36m",
        "\u001B[101m",
        "\u001B[102m",
        "\u001B[103m",
        "\u001B[104m",
        "\u001B[105m",
        "\u001B[106m"
    };
    
    // Mapa para recordar qué color le asignamos a cada usuario/grupo
    private final java.util.Map<String, String> coloresAsignados = new java.util.HashMap<>();
    private int indiceColor = 0;

    // Método para obtener (o asignar) un color consistente
    private String obtenerColor(String identificador) {
        if (!coloresAsignados.containsKey(identificador)) {
            // Asignamos un color secuencialmente y volvemos al inicio si se acaban
            coloresAsignados.put(identificador, COLORES[indiceColor % COLORES.length]);
            indiceColor++;
        }
        return coloresAsignados.get(identificador);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            HOST = args[0];
        } else {
            Scanner sc = new Scanner(System.in);
            System.out.print("IP del servidor [localhost]: ");
            String input = sc.nextLine().trim();
            if (!input.isEmpty()) HOST = input;
        }
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
            System.err.println("\u001B[4;37;41m Fallo de conexión: El servidor no esta disponible." + RESET);
        }
    }
    
    private void listarComandos() {
        System.out.println("\n --- Comandos disponibles ---");
        System.out.println("\u001B[32m /login <nombre_usuario>             * Iniciar sesión");
        System.out.println(" /msg <destinatario> <mensaje>       * Mensaje privado");
        System.out.println(" /creargrupo <id_grupo>              * Crear un grupo nuevo");
        System.out.println(" /unirse <id_grupo>                  * Unirse a un grupo existente");
        System.out.println(" /gmsg <id_grupo> <mensaje>          * Enviar mensaje al grupo");
        System.out.println(" /salir                              * Desconectarse");
        System.out.println(" /?                                  * Listar Comandos" + RESET);
    }

    private void procesarComandos(Scanner scanner) {
        listarComandos();
        
        try {
            while (true) {
                String input = scanner.nextLine();
                if (input == null || input.trim().isEmpty()) continue;

                String[] partes = input.split(" ", 3);
                String comando = partes[0].toLowerCase();

                switch (comando) {
                    case "/login":
                        if (partes.length == 2) {
                            if (miId != null) {
                                System.out.println("Ya estás registrado");
                            }
                            else {
                                miId = partes[1];
                                out.writeObject(new PaqueteLogin(miId));
                                out.flush();
                            }
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
                        try {
                            out.writeObject(new PaqueteLogout(miId));
                            out.flush();
                        } catch (IOException ignored) {}
                        System.exit(0);
                        break;
                        
                    case "/?":
                    listarComandos();

                    default:
                        System.out.println("Comando no reconocido. Escribe /login, /msg, /creargrupo, /unirse, /gmsg, /salir o /?.");
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
                        // Mensaje grupal: Obtenemos un color asignado al nombre del grupo
                        String color = obtenerColor(msj.getIdDestinatario());
                        System.out.println("\n" + color + "[Grupo " + msj.getIdDestinatario() + "] " 
                                + msj.getIdRemitente() + ":" + RESET + " " + msj.getContenido());
                    } else {
                        // Mensaje privado: Obtenemos un color asignado al remitente
                        String color = obtenerColor(msj.getIdRemitente());
                        System.out.println("\n" + color + "[" + msj.getIdRemitente() + "]:" + RESET 
                                + " " + msj.getContenido());
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