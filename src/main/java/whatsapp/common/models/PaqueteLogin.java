package whatsapp.common.models;

public class PaqueteLogin extends PaqueteRed {
    private static final long serialVersionUID = 1L;
    // idRemitente es el userId
    public PaqueteLogin(String userId) {
        super(userId);
    }
}