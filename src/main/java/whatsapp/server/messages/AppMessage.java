package whatsapp.server.messages;

import whatsapp.common.models.PaqueteRed;

public class AppMessage extends NodeMessage {
    private final PaqueteRed payload;

    public AppMessage(String source, String target, PaqueteRed payload, long lamport) {
        super(source, target, NodeMessageType.APP, lamport);
        this.payload = payload;
    }
    public PaqueteRed getPayload() { return payload; }
}