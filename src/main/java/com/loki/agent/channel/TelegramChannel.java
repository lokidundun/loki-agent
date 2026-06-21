package com.loki.agent.channel;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.bus.MessageBus;
import com.loki.agent.bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "loki.agent.telegram.enabled", havingValue = "true")
public class TelegramChannel extends TelegramLongPollingBot implements Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private final String botUsername;
    private final Set<Long> allowedUserIds;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private MessageBus bus;

    public TelegramChannel(
            @Value("${loki.agent.telegram.bot-token}") String botToken,
            @Value("${loki.agent.telegram.bot-username:loki_agent_bot}") String botUsername,
            @Value("${loki.agent.telegram.allowed-users:}") String allowedUsers) {
        super(botToken);
        this.botUsername = botUsername;
        this.allowedUserIds = parseAllowedUsers(allowedUsers);
        log.info("TelegramChannel created, allowedUsers={}", allowedUserIds);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void start(MessageBus bus) {
        this.bus = bus;
        running.set(true);

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram bot registered: {}", botUsername);
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot", e);
            return;
        }

        // Output loop: consume outbound messages and send via Telegram
        executor.submit(this::outputLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        long userId = update.getMessage().getFrom().getId();
        String chatId = String.valueOf(update.getMessage().getChatId());
        String text = update.getMessage().getText();
        String sender = update.getMessage().getFrom().getUserName();

        // Security: only allow whitelisted users
        if (!allowedUserIds.isEmpty() && !allowedUserIds.contains(userId)) {
            log.warn("Blocked message from unauthorized user {}", userId);
            return;
        }

        // Handle /start command
        if ("/start".equals(text)) {
            sendText(chatId, "Loki Agent is ready. Send me a message!");
            return;
        }

        InboundMessage msg = new InboundMessage(
                "telegram", sender != null ? sender : "user_" + userId,
                chatId, text, Instant.now(), List.of(), Map.of()
        );
        bus.publishInbound(msg);
        log.debug("Telegram inbound: [{}] {}", chatId, text);
    }

    private void outputLoop() {
        while (running.get()) {
            try {
                OutboundMessage msg = bus.consumeOutbound();
                if ("telegram".equals(msg.channel())) {
                    sendText(msg.chatId(), msg.content());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Telegram outputLoop error", e);
            }
        }
    }

    private void sendText(String chatId, String text) {
        // Telegram has a 4096 character limit per message
        if (text.length() > 4000) {
            text = text.substring(0, 4000) + "\n... (truncated)";
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message to {}", chatId, e);
        }
    }

    private Set<Long> parseAllowedUsers(String allowedUsers) {
        if (allowedUsers == null || allowedUsers.isBlank()) return Set.of();
        return Arrays.stream(allowedUsers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }
}
