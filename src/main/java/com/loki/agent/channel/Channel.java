package com.loki.agent.channel;

import com.loki.agent.bus.MessageBus;

public interface Channel {
    void start(MessageBus bus);
    void stop();
}
