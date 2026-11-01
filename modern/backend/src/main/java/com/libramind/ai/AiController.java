package com.libramind.ai;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;
import com.libramind.common.web.RequestIdFilter;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 会话、规则问答和实时SSE接口，登录身份在进入异步任务之前读取。 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    public record NewConversation(@Size(max = 120) String title) {}

    public record MessageInput(@NotBlank @Size(max = 2000) String content) {}

    public record Question(@NotBlank @Size(max = 1000) String question, Long editionId) {}

    private final ActorService actors;
    private final ConversationService conversations;
    private final AgentService agent;
    private final RagService rag;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiController(
            ActorService actors,
            ConversationService conversations,
            AgentService agent,
            RagService rag) {
        this.actors = actors;
        this.conversations = conversations;
        this.agent = agent;
        this.rag = rag;
    }

    @GetMapping("/conversations")
    public ApiResponse<java.util.List<ConversationService.Conversation>> list() {
        return ApiResponse.ok(conversations.list(actors.current()));
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationService.Conversation> create(
            @Valid @RequestBody NewConversation input) {
        return ApiResponse.ok(conversations.create(actors.current(), input.title()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<java.util.List<ConversationService.MessageView>> history(
            @PathVariable long id, @RequestParam(defaultValue = "0") long beforeId) {
        return ApiResponse.ok(conversations.messages(actors.current(), id, beforeId));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Map<String, Boolean>> archive(@PathVariable long id) {
        conversations.archive(actors.current(), id);
        return ApiResponse.ok(Map.of("archived", true));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AgentService.Reply> message(
            @PathVariable long id,
            @Valid @RequestBody MessageInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                agent.respond(
                        actors.current(),
                        id,
                        input.content(),
                        RequestIdFilter.id(request),
                        false,
                        (name, data) -> {}));
    }

    @PostMapping(value = "/conversations/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long id,
            @Valid @RequestBody MessageInput input,
            HttpServletRequest request) {
        var actor = actors.current();
        // 提前验证归属，避免以200的SSE包裹明显的404。
        conversations.messages(actor, id, 0);
        String requestId = RequestIdFilter.id(request);
        SseEmitter emitter = new SseEmitter(150_000L);
        var disconnected = new java.util.concurrent.atomic.AtomicBoolean();
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onError(error -> disconnected.set(true));
        executor.submit(
                () -> {
                    org.slf4j.MDC.put("requestId", requestId);
                    try {
                        agent.respond(
                                actor,
                                id,
                                input.content(),
                                requestId,
                                true,
                                (name, data) -> {
                                    if (disconnected.get()) {
                                        throw new IllegalStateException("Client disconnected");
                                    }
                                    try {
                                        emitter.send(
                                                SseEmitter.event()
                                                        .name(name)
                                                        .data(data, MediaType.APPLICATION_JSON));
                                    } catch (java.io.IOException failure) {
                                        throw new IllegalStateException("Client disconnected");
                                    }
                                });
                        emitter.complete();
                    } catch (Exception failure) {
                        if (!disconnected.get()) {
                            try {
                                String code =
                                        failure instanceof BusinessException business
                                                ? business.code()
                                                : "AI_UNAVAILABLE";
                                emitter.send(
                                        SseEmitter.event()
                                                .name("error")
                                                .data(
                                                        Map.of(
                                                                "code",
                                                                code,
                                                                "message",
                                                                "本轮回答未完成，请使用普通查询或稍后重试",
                                                                "requestId",
                                                                requestId)));
                                emitter.complete();
                            } catch (java.io.IOException ignored) {
                                emitter.completeWithError(
                                        new IllegalStateException("Stream closed"));
                            }
                        }
                    } finally {
                        org.slf4j.MDC.clear();
                    }
                });
        return emitter;
    }

    @PostMapping("/ask")
    public ApiResponse<RagService.Answer> ask(@Valid @RequestBody Question input) {
        return ApiResponse.ok(rag.answer(actors.current(), input.question(), input.editionId()));
    }

    @PreDestroy
    void close() {
        executor.close();
    }
}
