package com.loki.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<String, List<Consumer<Event>>> listeners = new ConcurrentHashMap<>();

    public void on(String eventType, Consumer<Event> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void emit(String eventType, Event event) {
        List<Consumer<Event>> subs = listeners.get(eventType);
        if (subs == null || subs.isEmpty()) return;

        for (Consumer<Event> listener : subs) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("EventBus listener error for {}: {}", eventType, e.getMessage());
            }
        }
    }

    public void emit(String eventType, Map<String, Object> data) {
        emit(eventType, new Event(eventType, data));
    }

    public int listenerCount(String eventType) {
        List<Consumer<Event>> subs = listeners.get(eventType);
        return subs == null ? 0 : subs.size();
    }
}
