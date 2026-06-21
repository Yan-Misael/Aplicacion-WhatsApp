package whatsapp.server.messages;

public class UserLogoutAnnounceMessage extends NodeMessage {
    private final String userId;

    public UserLogoutAnnounceMessage(String sourceNodeId, String targetNodeId, String userId, long lamportTimestamp) {
        super(sourceNodeId, targetNodeId, NodeMessageType.USER_LOGOUT_ANNOUNCE, lamportTimestamp);
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
