package com.loki.agent.channel;

import com.loki.agent.bus.MessageBus;
import com.loki.agent.llm.StreamingCallback;

public interface Channel {
    void start(MessageBus bus);
    void stop();

    /**
     * Returns a streaming callback for real-time token output, or null if not supported.
     */
    default StreamingCallback getStreamingCallback() { return null; }
}
