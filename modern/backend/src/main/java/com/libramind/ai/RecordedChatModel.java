package com.libramind.ai;

import com.libramind.common.api.BusinessException;
import com.libramind.common.db.IdempotencyService;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 按提示摘要精确匹配真实录制的模型响应，用于无网络演示，不编造通用模型回答。 */
@Component
public class RecordedChatModel implements ChatModel {
    public record Recording(
            String mode,
            String model,
            Instant recordedAt,
            String text,
            List<AssistantMessage.ToolCall> toolCalls) {}

    private final Path root;
    private final ObjectMapper json;

    public RecordedChatModel(
            @Value("${app.ai.replay-directory:./data/replays}") String directory,
            ObjectMapper json) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.json = json;
    }

    /** 只保存模型输出及请求摘要，不保存API密钥、提示正文或ToolContext中的登录身份。 */
    public void record(Prompt prompt, ChatResponse response) {
        var output = response.getResult().getOutput();
        var recording =
                new Recording(
                        "recorded_replay",
                        AiGateway.CHAT_MODEL,
                        Instant.now(),
                        output.getText(),
                        output.getToolCalls());
        try {
            Files.createDirectories(root);
            Files.writeString(
                    root.resolve(fingerprint(prompt) + ".json"),
                    json.writeValueAsString(recording));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot save explicit replay recording");
        }
    }

    @Override
    public ChatOptions getOptions() {
        return OpenAiChatOptions.builder().model(AiGateway.CHAT_MODEL).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Path file = root.resolve(fingerprint(prompt) + ".json");
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "REPLAY_MISS",
                    "没有匹配这轮上下文的录制响应；请使用已录制的演示问题或切回实时模式");
        }
        try {
            Recording recording = json.readValue(Files.readString(file), Recording.class);
            if (!recording.mode().equals("recorded_replay")
                    || !recording.model().equals(AiGateway.CHAT_MODEL)) {
                throw new IllegalStateException("Replay model mismatch");
            }
            var message =
                    AssistantMessage.builder()
                            .content(recording.text() == null ? "" : recording.text())
                            .toolCalls(recording.toolCalls())
                            .build();
            return new ChatResponse(List.of(new Generation(message)));
        } catch (java.io.IOException failure) {
            throw AiGateway.unavailable();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt)));
    }

    private String fingerprint(Prompt prompt) {
        var values =
                prompt.getInstructions().stream()
                        .map(
                                message -> {
                                    if (message instanceof ToolResponseMessage tool) {
                                        return Map.of(
                                                "role", "tool", "responses", tool.getResponses());
                                    }
                                    if (message instanceof AssistantMessage assistant) {
                                        return Map.of(
                                                "role",
                                                "assistant",
                                                "text",
                                                assistant.getText() == null
                                                        ? ""
                                                        : assistant.getText(),
                                                "toolCalls",
                                                assistant.getToolCalls());
                                    }
                                    return Map.of(
                                            "role",
                                            message.getMessageType().name(),
                                            "text",
                                            message.getText());
                                })
                        .toList();
        return IdempotencyService.digest(
                json.writeValueAsString(values.stream().map(java.util.TreeMap::new).toList()));
    }
}
