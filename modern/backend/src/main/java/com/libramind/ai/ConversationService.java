package com.libramind.ai;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按账号保存对话和最近上下文，数据库租约阻止同一会话同时生成两轮回答。 */
@Service
public class ConversationService {
    public record Conversation(long id, String title, Instant updatedAt) {}

    public record MessageView(
            long id,
            String role,
            String content,
            String model,
            List<Long> editionReferences,
            List<com.libramind.search.KnowledgeService.Citation> citations,
            Instant createdAt) {}

    public record Turn(
            long conversationId, String lease, List<Message> history, List<Long> candidates) {}

    private final SqlStore sql;
    private final ObjectMapper json;

    public ConversationService(SqlStore sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public List<Conversation> list(CurrentUser actor) {
        return sql
                .list(
                        "SELECT * FROM ai_conversation WHERE user_id=? AND status='ACTIVE' ORDER BY"
                                + " updated_at DESC,id DESC LIMIT 100",
                        actor.id())
                .stream()
                .map(
                        r ->
                                new Conversation(
                                        number(r, "id"),
                                        text(r, "title"),
                                        instant(r, "updated_at")))
                .toList();
    }

    public Conversation create(CurrentUser actor, String title) {
        String name = title == null || title.isBlank() ? "新会话" : title.strip();
        if (name.length() > 120) {
            throw BusinessException.invalid("会话标题最多120字");
        }
        long id =
                sql.insert(
                        "INSERT INTO ai_conversation(user_id,title) VALUES(?,?)", actor.id(), name);
        return new Conversation(id, name, Instant.now());
    }

    public List<MessageView> messages(CurrentUser actor, long id, long beforeId) {
        owned(actor, id);
        var rows =
                sql.list(
                        "SELECT * FROM ai_message WHERE conversation_id=? AND id<? ORDER BY id DESC"
                                + " LIMIT 50",
                        id,
                        beforeId <= 0 ? Long.MAX_VALUE : beforeId);
        List<MessageView> result = new ArrayList<>();
        for (var row : rows) {
            result.add(
                    new MessageView(
                            number(row, "id"),
                            text(row, "role"),
                            text(row, "content"),
                            text(row, "model"),
                            row.get("structured_result_refs") == null
                                    ? List.of()
                                    : List.of(
                                            json.readValue(
                                                    text(row, "structured_result_refs"),
                                                    Long[].class)),
                            row.get("citations") == null
                                    ? List.of()
                                    : List.of(
                                            json.readValue(
                                                    text(row, "citations"),
                                                    com.libramind.search.KnowledgeService.Citation[]
                                                            .class)),
                            instant(row, "created_at")));
        }
        Collections.reverse(result);
        return result;
    }

    @Transactional
    public void archive(CurrentUser actor, long id) {
        owned(actor, id);
        if (sql.update(
                        "UPDATE ai_conversation SET status='ARCHIVED' WHERE id=? AND (busy_until IS"
                                + " NULL OR busy_until<UTC_TIMESTAMP(6))",
                        id)
                != 1) {
            throw BusinessException.conflict("CONVERSATION_BUSY", "请等待本轮回答结束");
        }
        sql.update(
                "UPDATE pending_action SET status='CANCELLED' WHERE conversation_id=? AND"
                        + " status='PENDING'",
                id);
    }

    /** 领取一轮生成资格，并把最多十二条历史消息作为上下文；不跨用户查询。 */
    @Transactional
    public Turn begin(CurrentUser actor, long id, String input) {
        owned(actor, id);
        if (input == null || input.isBlank() || input.length() > 2000) {
            throw BusinessException.invalid("消息不能为空且最多2000字");
        }
        String token = UUID.randomUUID().toString();
        if (sql.update(
                        """
UPDATE ai_conversation SET busy_token=?,busy_until=UTC_TIMESTAMP(6)+INTERVAL 180 SECOND,
updated_at=UTC_TIMESTAMP(6) WHERE id=? AND user_id=? AND status='ACTIVE'
AND (busy_until IS NULL OR busy_until<UTC_TIMESTAMP(6))
""",
                        token,
                        id,
                        actor.id())
                != 1) {
            throw BusinessException.conflict("CONVERSATION_BUSY", "当前会话正在生成回答，请稍后再发");
        }
        var previous =
                sql.list(
                        "SELECT * FROM ai_message WHERE conversation_id=? ORDER BY id DESC LIMIT"
                                + " 12",
                        id);
        List<Message> history = new ArrayList<>();
        List<Long> candidates = List.of();
        int chars = 0;
        for (var row : previous) {
            String content = text(row, "content");
            if (chars + content.length() > 10000) {
                break;
            }
            chars += content.length();
            history.add(
                    "USER".equals(text(row, "role"))
                            ? new UserMessage(content)
                            : new AssistantMessage(content));
            if (candidates.isEmpty() && row.get("structured_result_refs") != null) {
                candidates =
                        List.of(json.readValue(text(row, "structured_result_refs"), Long[].class));
            }
        }
        Collections.reverse(history);
        sql.insert(
                "INSERT INTO ai_message(conversation_id,role,content) VALUES(?,'USER',?)",
                id,
                input.strip());
        return new Turn(id, token, history, candidates);
    }

    @Transactional
    public void finish(
            CurrentUser actor,
            Turn turn,
            String content,
            List<Long> refs,
            Object citations,
            long milliseconds,
            boolean live) {
        var row =
                sql.one(
                        "SELECT * FROM ai_conversation WHERE id=? AND user_id=? AND busy_token=?"
                                + " FOR UPDATE",
                        turn.conversationId(),
                        actor.id(),
                        turn.lease());
        if (row == null) {
            throw BusinessException.conflict("TURN_EXPIRED", "本轮生成资格已过期");
        }
        sql.insert(
                """
INSERT INTO ai_message(conversation_id,role,content,model,latency_ms,structured_result_refs,citations)
VALUES(?,'ASSISTANT',?,?,?,?,?)
""",
                turn.conversationId(),
                content,
                live ? AiGateway.CHAT_MODEL : "recorded_replay:" + AiGateway.CHAT_MODEL,
                milliseconds,
                json.writeValueAsString(refs),
                json.writeValueAsString(citations));
        sql.update(
                "UPDATE ai_conversation SET"
                        + " busy_token=NULL,busy_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                turn.conversationId());
    }

    public void release(CurrentUser actor, Turn turn) {
        sql.update(
                "UPDATE ai_conversation SET busy_token=NULL,busy_until=NULL WHERE id=? AND"
                        + " user_id=? AND busy_token=?",
                turn.conversationId(),
                actor.id(),
                turn.lease());
    }

    private Object parse(Map<String, Object> row, String field) {
        return row.get(field) == null ? null : json.readValue(text(row, field), Object.class);
    }

    private void owned(CurrentUser actor, long id) {
        if (sql.count(
                        "SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=? AND"
                                + " status='ACTIVE'",
                        id,
                        actor.id())
                == 0) {
            throw BusinessException.notFound();
        }
    }
}
