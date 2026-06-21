package com.loki.agent.bus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InboundMessage(
        String channel,
        String sender,
        String chatId,
        String content,
        Instant timestamp,
        List<String> media,
        Map<String, Object> metadata
) {
    public String sessionKey() {
        return channel + ":" + chatId;
    }
}
