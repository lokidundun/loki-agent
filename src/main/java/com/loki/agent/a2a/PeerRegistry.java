package com.loki.agent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks known peer agents and their capabilities.
 */
@Component
public class PeerRegistry {

    private static final Logger log = LoggerFactory.getLogger(PeerRegistry.class);

    private final ConcurrentHashMap<String, AgentCard> peers = new ConcurrentHashMap<>();

    public void register(AgentCard card) {
        peers.put(card.name(), card);
        log.info("Peer registered: {} at {}", card.name(), card.endpoint());
    }

    public void remove(String name) {
        peers.remove(name);
    }

    public AgentCard get(String name) {
        return peers.get(name);
    }

    public List<AgentCard> all() {
        return List.copyOf(peers.values());
    }

    public boolean hasPeer(String name) {
        return peers.containsKey(name);
    }
}
