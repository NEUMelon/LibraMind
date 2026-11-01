package com.libramind.ai;

import com.libramind.common.api.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 固定使用国产模型接入 Aihubmix，不接受前端指定模型或自动回退。 */
@Service
public class AiGateway {

    private static final Logger LOG = LoggerFactory.getLogger(AiGateway.class);

    public static final String CHAT_MODEL = "qwen3.5-flash";
    public static final String EMBEDDING_MODEL = "text-embedding-v4";
    public static final int DIMENSION = 1024;
    public static final String MODEL_KEY = EMBEDDING_MODEL + ":1024:v1";

    private final AiBudgetService budget;
    private final String keyFile;
    private final String configuredKey;
    private final String mode;
    private final RecordedChatModel replay;
    private final boolean recordReplay;
    private volatile OpenAiChatModel chat;
    private volatile OpenAiEmbeddingModel embedding;

    public AiGateway(
            AiBudgetService budget,
            @Value("${app.ai.key-file:}") String keyFile,
            @Value("${app.ai.api-key:}") String configuredKey,
            @Value("${app.ai.mode:live}") String mode,
            RecordedChatModel replay,
            @Value("${app.ai.record-replay:false}") boolean recordReplay) {
        this.budget = budget;
        this.keyFile = keyFile;
        this.configuredKey = configuredKey;
        this.mode = mode;
        this.replay = replay;
        this.recordReplay = recordReplay;
    }

    public boolean isLive() {
        return "live".equals(mode);
    }

    public String mode() {
        return mode;
    }

    /** 每次业务请求创建独立的预算计数器，最多允许6轮模型调用。 */
    public ChatClient client(Long userId, String type) {
        if ("replay".equals(mode)) {
            if (!"AGENT".equals(type)) {
                throw new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "REPLAY_MISS",
                        "回放仅支持已录制的文字助手场景；识图与独立问答请切回实时模式");
            }
            return ChatClient.builder(replay).build();
        }
        requireLive();
        return ChatClient.builder(new BudgetedModel(chatModel(), userId, type)).build();
    }

    /** 为少量文本生成归一化检索向量；不在数据库事务内执行网络请求。 */
    public List<float[]> embed(List<String> texts, Long userId) {
        requireLive();
        if (texts.isEmpty()
                || texts.size() > 16
                || texts.stream().mapToInt(String::length).sum() > 24000) {
            throw BusinessException.invalid("向量请求超过批次或文本长度上限");
        }
        long id = budget.reserve(userId, "EMBEDDING", EMBEDDING_MODEL);
        long started = System.nanoTime();
        try {
            var response =
                    embeddingModel()
                            .call(
                                    new org.springframework.ai.embedding.EmbeddingRequest(
                                            texts,
                                            OpenAiEmbeddingOptions.builder()
                                                    .model(EMBEDDING_MODEL)
                                                    .dimensions(DIMENSION)
                                                    .build()));
            List<float[]> result =
                    response.getResults().stream().map(item -> item.getOutput()).toList();
            if (result.size() != texts.size()
                    || result.stream().anyMatch(vector -> vector.length != DIMENSION)) {
                throw new IllegalStateException("Embedding dimensions do not match");
            }
            int tokens =
                    response.getMetadata().getUsage() == null
                            ? texts.stream().mapToInt(String::length).sum()
                            : response.getMetadata().getUsage().getTotalTokens();
            budget.success(id, EMBEDDING_MODEL, tokens, 0, elapsed(started));
            return result;
        } catch (Exception exception) {
            diagnostic(exception);
            budget.failure(id, elapsed(started));
            throw unavailable();
        }
    }

    private synchronized OpenAiChatModel chatModel() {
        if (chat == null) {
            chat =
                    OpenAiChatModel.builder()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .apiKey(key())
                                            .baseUrl("https://aihubmix.com/v1")
                                            .model(CHAT_MODEL)
                                            .temperature(0.1)
                                            .maxTokens(1024)
                                            .maxRetries(0)
                                            .timeout(Duration.ofSeconds(45))
                                            .streamUsage(true)
                                            .parallelToolCalls(false)
                                            .extraBody(Map.of("enable_thinking", false))
                                            .build())
                            .build();
        }
        return chat;
    }

    private synchronized OpenAiEmbeddingModel embeddingModel() {
        if (embedding == null) {
            embedding =
                    OpenAiEmbeddingModel.builder()
                            .options(
                                    OpenAiEmbeddingOptions.builder()
                                            .apiKey(key())
                                            .baseUrl("https://aihubmix.com/v1")
                                            .model(EMBEDDING_MODEL)
                                            .dimensions(DIMENSION)
                                            .maxRetries(0)
                                            .timeout(Duration.ofSeconds(45))
                                            .build())
                            .build();
        }
        return embedding;
    }

    private String key() {
        String value = configuredKey;
        if (value.isBlank() && !keyFile.isBlank()) {
            try {
                value =
                        Files.readString(Path.of(keyFile), StandardCharsets.UTF_8)
                                .replace("\uFEFF", "")
                                .trim();
            } catch (java.io.IOException exception) {
                throw unavailable();
            }
        }
        if (value.isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private void requireLive() {
        if (!isLive()) {
            throw unavailable();
        }
    }

    public static BusinessException unavailable() {
        return new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "模型服务暂不可用，请使用普通检索和业务页面");
    }

    private static void diagnostic(Exception exception) {
        String status =
                exception instanceof com.openai.errors.OpenAIServiceException service
                        ? Integer.toString(service.statusCode())
                        : "not-http";
        LOG.warn(
                "AI provider failure category={}, status={}, cause={}",
                exception.getClass().getSimpleName(),
                status,
                exception.getCause() == null
                        ? "none"
                        : exception.getCause().getClass().getSimpleName());
        java.util.Arrays.stream(exception.getStackTrace())
                .limit(5)
                .forEach(frame -> LOG.warn("AI failure frame={}", frame));
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** 拦截每一次模型调用，工具循环也必须经过预算和轮次限制。 */
    private class BudgetedModel implements ChatModel {
        private final ChatModel delegate;
        private final Long userId;
        private final String type;
        private final AtomicInteger rounds = new AtomicInteger();

        BudgetedModel(ChatModel delegate, Long userId, String type) {
            this.delegate = delegate;
            this.userId = userId;
            this.type = type;
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return delegate.getOptions();
        }

        private long reserve(Prompt prompt) {
            if (rounds.incrementAndGet() > 6 || prompt.getContents().length() > 24000) {
                throw new BusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "AI_REQUEST_LIMIT", "本轮请求超过文本或工具调用上限");
            }
            return budget.reserve(userId, type, CHAT_MODEL);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            long id = reserve(prompt);
            long started = System.nanoTime();
            try {
                ChatResponse response = delegate.call(prompt);
                finish(id, started, response.getMetadata().getUsage());
                if (recordReplay && "AGENT".equals(type)) {
                    replay.record(prompt, response);
                }
                return response;
            } catch (Exception exception) {
                diagnostic(exception);
                budget.failure(id, elapsed(started));
                throw unavailable();
            }
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(
                    () -> {
                        long id = reserve(prompt);
                        long started = System.nanoTime();
                        AtomicReference<Usage> usage = new AtomicReference<>();
                        Flux<ChatResponse> responses = delegate.stream(prompt);
                        if (recordReplay && "AGENT".equals(type)) {
                            responses =
                                    new org.springframework.ai.chat.model.MessageAggregator()
                                            .aggregate(
                                                    responses,
                                                    response -> replay.record(prompt, response));
                        }
                        return responses
                                .doOnNext(
                                        response -> {
                                            if (response.getMetadata().getUsage() != null) {
                                                usage.set(response.getMetadata().getUsage());
                                            }
                                        })
                                .doOnComplete(() -> finish(id, started, usage.get()))
                                .doOnError(error -> budget.failure(id, elapsed(started)))
                                .doFinally(
                                        signal -> {
                                            if (signal
                                                    == reactor.core.publisher.SignalType.CANCEL) {
                                                budget.failure(id, elapsed(started));
                                            }
                                        })
                                .onErrorMap(error -> unavailable());
                    });
        }

        private void finish(long id, long started, Usage usage) {
            if (usage == null || usage.getTotalTokens() == 0) {
                budget.failure(id, elapsed(started));
            } else {
                budget.success(
                        id,
                        CHAT_MODEL,
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        elapsed(started));
            }
        }
    }
}
