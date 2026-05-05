package whatsapp.common.models;

/**
 * Paquete serializable que representa la solicitud de un usuario de unirse
 * a un grupo ya existente en el servidor.
 *
 * Se distingue de PaqueteCrearGrupo en que no crea el grupo: solo agrega
 * al remitente como nuevo miembro si el grupo existe.
 *
 */
public class PaqueteUnirseGrupo extends PaqueteRed {
    private static final long serialVersionUID = 1L;

    private final String idGrupo;

    /**
     * @param idUsuario ID del usuario que quiere unirse al grupo.
     * @param idGrupo   ID del grupo al que desea unirse.
     */
    public PaqueteUnirseGrupo(String idUsuario, String idGrupo) {
        super(idUsuario);
        this.idGrupo = idGrupo;
    }

    public String getIdGrupo() {
        return idGrupo;
    }
}
