package com.libramind.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 显式启用才调用付费接口，默认集成测试不依赖模型，也不打印凭据。 */
@SpringBootTest(
        properties = {
            "spring.config.import=file:./config/test-local.properties",
            "app.ai.index-worker-enabled=false"
        })
@EnabledIfSystemProperty(named = "libramind.live-tests", matches = "true")
class LiveAiIT extends com.libramind.IntegrationSupport {

    @Autowired AiGateway ai;
    @Autowired AgentService agent;
    @Autowired ConversationService conversations;
    @Autowired com.libramind.loan.LoanService loans;
    @Autowired com.libramind.vision.VisionService vision;

    @Value("${app.ai.key-file}")
    String keyFile;

    @Test
    void configuredCheapModelsWorkThroughSpringAi() {
        assertThat(Files.isRegularFile(Path.of(keyFile))).isTrue();
        String reply = ai.client(null, "SMOKE").prompt().user("只回复：连接成功").call().content();
        assertThat(reply).contains("连接成功");
        var vectors = ai.embed(List.of("图书馆借阅规则"), null);
        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst()).hasSize(1024);
    }

    @Test
    void realStreamingToolsUseAuthenticatedIdentityAndPersistFinalMessage() {
        var reader = reader();
        var copy = copy();
        loans.borrow(
                reader,
                new com.libramind.loan.LoanDtos.BorrowInput(
                        null, List.of(copy.accessionNo()), "模型工具验证"),
                null);
        var conversation = conversations.create(reader, "真实流式工具验证");
        var events = new java.util.ArrayList<String>();
        var reply =
                agent.respond(
                        reader,
                        conversation.id(),
                        "请调用getMyLoans，查询我当前借了哪些书和应还日期，不要执行任何操作。",
                        java.util.UUID.randomUUID().toString(),
                        true,
                        (name, data) -> events.add(name));
        assertThat(reply.tools())
                .anySatisfy(tool -> assertThat(tool.name()).isEqualTo("getMyLoans"));
        assertThat(events).contains("meta", "tool", "delta", "result", "done");
        assertThat(reply.actions()).isEmpty();
        assertThat(conversations.messages(reader, conversation.id(), 0)).hasSize(2);
    }

    @Test
    void actualCoverInputAndStructuredExtractionWork() throws Exception {
        Path cover =
                Path.of(System.getProperty("libramind.test.cover", "data/covers/9787115147318.jpg"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(cover), "Set -Dlibramind.test.cover to an accessible cover image");
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "cover.jpg", "image/jpeg", Files.readAllBytes(cover));
        var result = vision.recognize(admin, file, false);
        assertThat(result.model()).isEqualTo(AiGateway.CHAT_MODEL);
        assertThat(result.extracted().title()).isNotBlank();
        assertThat(result.recognitionId()).isPositive();
    }
}
