package whatsapp.server.messages;

public class UserLoginAnnounceMessage extends NodeMessage {
    private final String userId;

    public UserLoginAnnounceMessage(String sourceNodeId, String targetNodeId, String userId, long lamportTimestamp) {
        super(sourceNodeId, targetNodeId, NodeMessageType.USER_LOGIN_ANNOUNCE, lamportTimestamp);
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
