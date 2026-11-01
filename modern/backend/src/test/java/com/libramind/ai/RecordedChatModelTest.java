package com.libramind.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

class RecordedChatModelTest {
    @TempDir Path directory;

    @Test
    void exactRecordedRequestReplaysWithoutNetworkAndUnknownContextIsRejected() {
        var model = new RecordedChatModel(directory.toString(), new ObjectMapper());
        var prompt = new Prompt("固定测试问题");
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("固定测试输出"))));
        model.record(prompt, response);
        var restarted = new RecordedChatModel(directory.toString(), new ObjectMapper());
        assertThat(restarted.call(prompt).getResult().getOutput().getText()).isEqualTo("固定测试输出");
        assertThat(restarted.stream(prompt).blockFirst().getResult().getOutput().getText())
                .isEqualTo("固定测试输出");
        assertThatThrownBy(() -> restarted.call(new Prompt("没有录过的问题")))
                .isInstanceOf(com.libramind.common.api.BusinessException.class)
                .hasMessageContaining("没有匹配");
    }
}
