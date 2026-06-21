package com.loki.agent.bus;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg) {
        inbound.offer(msg);
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    public void publishOutbound(OutboundMessage msg) {
        outbound.offer(msg);
    }

    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public int inboundSize() {
        return inbound.size();
    }

    public int outboundSize() {
        return outbound.size();
    }
}
