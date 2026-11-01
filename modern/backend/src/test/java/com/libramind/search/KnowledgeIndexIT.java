package com.libramind.search;

import static com.libramind.common.db.SqlStore.*;

import static org.assertj.core.api.Assertions.*;

import com.libramind.IntegrationSupport;
import com.libramind.ai.AiGateway;
import com.libramind.search.KnowledgeService.KnowledgeInput;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

class KnowledgeIndexIT extends IntegrationSupport {
    @Autowired KnowledgeService knowledge;
    @Autowired VectorIndexService vectors;
    @Autowired SearchService search;

    @Test
    void oldLeaseCannotCompleteAfterKnowledgeUpdateAndNewVectorSurvivesReload() {
        var book = book("并发知识版本测试" + UUID.randomUUID());
        var document = knowledge.create(admin, input(book.id(), "第一版内容", "PUBLIC"));
        var task = task(document.id());
        String oldHash = text(task, "content_hash");
        claim(number(task, "id"), "old-lease");
        knowledge.update(admin, document.id(), input(book.id(), "第二版内容", "PUBLIC"));
        assertThat(vectors.complete(number(task, "id"), "old-lease", oldHash, vector())).isFalse();
        var latest = task(document.id());
        claim(number(latest, "id"), "new-lease");
        assertThat(
                        vectors.complete(
                                number(latest, "id"),
                                "new-lease",
                                text(latest, "content_hash"),
                                vector()))
                .isTrue();
        vectors.reload();
        assertThat(vectors.search(admin, vector(), 100))
                .extracting(VectorIndexService.ScoredChunk::chunkId)
                .contains(number(latest, "chunk_id"));
        knowledge.delete(admin, document.id(), "测试删除资料");
        assertThat(vectors.search(admin, vector(), 100))
                .extracting(VectorIndexService.ScoredChunk::chunkId)
                .doesNotContain(number(latest, "chunk_id"));
    }

    @Test
    void privateAndExpiredChunksAreFilteredBeforeResultsLeaveServer() {
        var reader = reader();
        var book = book("管理员私有资料测试");
        var document = knowledge.create(admin, input(book.id(), "只给管理员的片段", "ADMIN"));
        var task = task(document.id());
        claim(number(task, "id"), "private-lease");
        vectors.complete(number(task, "id"), "private-lease", text(task, "content_hash"), vector());
        assertThat(knowledge.validChunk(reader, number(task, "chunk_id"), null)).isNull();
        assertThat(vectors.search(reader, vector(), 100))
                .extracting(VectorIndexService.ScoredChunk::chunkId)
                .doesNotContain(number(task, "chunk_id"));
        assertThat(knowledge.validChunk(admin, number(task, "chunk_id"), "wrong-hash")).isNull();
    }

    @Test
    void keywordSearchUsesRealBookAndIsbnValidationIsDeterministic() {
        String unique = "唯一测试书" + UUID.randomUUID();
        var book = book(unique);
        assertThat(search.search(null, unique, "keyword", 10, false).hits())
                .extracting(h -> h.book().id())
                .contains(book.id());
        assertThat(com.libramind.catalog.Isbn.normalize("0-13-468599-7"))
                .isEqualTo("9780134685991");
        assertThatThrownBy(() -> com.libramind.catalog.Isbn.normalize("9780134685992"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> VectorIndexService.normalize(new float[1024]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeInput input(long edition, String content, String visibility) {
        return new KnowledgeInput(edition, "测试资料", content, "MANUAL_NOTE", "自动测试", "", visibility);
    }

    private java.util.Map<String, Object> task(long document) {
        return sql.one(
                "SELECT t.* FROM index_task t JOIN knowledge_chunk c ON c.id=t.chunk_id WHERE"
                        + " c.document_id=? ORDER BY t.id LIMIT 1",
                document);
    }

    private void claim(long id, String token) {
        sql.update(
                "UPDATE index_task SET"
                    + " status='PROCESSING',claim_token=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 120"
                    + " SECOND WHERE id=?",
                token,
                id);
    }

    private float[] vector() {
        var v = new float[AiGateway.DIMENSION];
        v[0] = 1;
        return v;
    }
}
