package com.libramind.search;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.ai.AiGateway;
import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.common.db.SqlStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例内存向量索引，数据库持久化为事实来源，任务领取使用短事务与租约。 */
@Service
public class VectorIndexService implements ApplicationRunner {

    public record ScoredChunk(long chunkId, long editionId, double score) {}

    public record IndexStatus(
            boolean ready,
            int loaded,
            long pending,
            long failed,
            long stale,
            String modelKey,
            boolean workerEnabled) {}

    private record Entry(long chunkId, String hash, float[] vector) {}

    private final ConcurrentHashMap<Long, Entry> vectors = new ConcurrentHashMap<>();
    private final SqlStore sql;
    private final KnowledgeService knowledge;
    private final AiGateway ai;
    private final TransactionTemplate transaction;
    private final boolean workerEnabled;
    private volatile boolean ready;

    public VectorIndexService(
            SqlStore sql,
            KnowledgeService knowledge,
            AiGateway ai,
            PlatformTransactionManager manager,
            @Value("${app.ai.index-worker-enabled:false}") boolean workerEnabled) {
        this.sql = sql;
        this.knowledge = knowledge;
        this.ai = ai;
        this.transaction = new TransactionTemplate(manager);
        this.workerEnabled = workerEnabled;
    }

    /** 重启后恢复持久化向量，并重新登记缺失或过期任务；只支持单应用实例。 */
    @Override
    public void run(ApplicationArguments args) {
        sql.update(
                "UPDATE index_task SET status='PENDING',claim_token=NULL,lease_until=NULL WHERE"
                        + " status='PROCESSING'");
        sql.update(
                """
INSERT INTO index_task(chunk_id,content_hash)
SELECT c.id,c.content_hash FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
LEFT JOIN chunk_embedding e ON e.chunk_id=c.id AND e.model_key=?
WHERE c.enabled=TRUE AND d.enabled=TRUE AND (e.chunk_id IS NULL OR e.content_hash<>c.content_hash)
ON DUPLICATE KEY UPDATE content_hash=VALUES(content_hash),status='PENDING',attempts=0,
claim_token=NULL,lease_until=NULL,next_retry_at=UTC_TIMESTAMP(6)
""",
                AiGateway.MODEL_KEY);
        reload();
    }

    /** 从数据库建立快照；不同模型或维度的向量不能混用。 */
    public synchronized void reload() {
        ready = false;
        vectors.clear();
        if (sql.count(
                        "SELECT COUNT(*) FROM chunk_embedding WHERE model_key<>?",
                        AiGateway.MODEL_KEY)
                > 0) {
            return;
        }
        for (var row :
                sql.list("SELECT * FROM chunk_embedding WHERE model_key=?", AiGateway.MODEL_KEY)) {
            float[] vector = decode((byte[]) row.get("vector"));
            vectors.put(
                    number(row, "chunk_id"),
                    new Entry(number(row, "chunk_id"), text(row, "content_hash"), vector));
        }
        ready = true;
    }

    /** 单worker批量处理，网络调用位于事务外，完成时再次核对领取资格与内容版本。 */
    @Scheduled(fixedDelayString = "${app.ai.index-worker-delay-ms:5000}")
    public synchronized void processBatch() {
        if (!workerEnabled || !ai.isLive()) {
            return;
        }
        String token = UUID.randomUUID().toString();
        List<Map<String, Object>> batch =
                transaction.execute(
                        status -> {
                            sql.update(
                                    "UPDATE index_task SET"
                                        + " status='FAILED',last_error='LEASE_EXHAUSTED' WHERE"
                                        + " status='PROCESSING' AND lease_until<UTC_TIMESTAMP(6)"
                                        + " AND attempts>=3");
                            var tasks =
                                    sql.list(
                                            """
SELECT t.*,c.content FROM index_task t JOIN knowledge_chunk c ON c.id=t.chunk_id
WHERE t.attempts<3 AND ((t.status='PENDING' AND t.next_retry_at<=UTC_TIMESTAMP(6))
OR (t.status='PROCESSING' AND t.lease_until<UTC_TIMESTAMP(6))) ORDER BY t.id LIMIT 8 FOR UPDATE
""");
                            for (var task : tasks) {
                                sql.update(
                                        "UPDATE index_task SET"
                                            + " status='PROCESSING',claim_token=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL"
                                            + " 120 SECOND,attempts=attempts+1 WHERE id=?",
                                        token,
                                        task.get("id"));
                            }
                            return tasks;
                        });
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> upserts =
                    batch.stream()
                            .filter(task -> "UPSERT".equals(text(task, "operation")))
                            .toList();
            List<float[]> generated =
                    upserts.isEmpty()
                            ? List.of()
                            : ai.embed(
                                    upserts.stream().map(task -> text(task, "content")).toList(),
                                    null);
            for (var task : batch) {
                int index = upserts.indexOf(task);
                float[] value = index < 0 ? null : generated.get(index);
                complete(number(task, "id"), token, text(task, "content_hash"), value);
            }
        } catch (Exception exception) {
            transaction.executeWithoutResult(
                    status -> {
                        for (var task : batch) {
                            sql.update(
                                    """
UPDATE index_task SET status=IF(attempts>=3,'FAILED','PENDING'),
next_retry_at=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND,last_error='EMBEDDING_UNAVAILABLE',
claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=?
""",
                                    task.get("id"),
                                    token);
                        }
                    });
        }
    }

    /** 原子校验旧任务不能写回；返回false表示本次结果已经失效。 */
    public boolean complete(long taskId, String token, String hash, float[] vector) {
        Boolean result =
                transaction.execute(
                        status -> {
                            var reference =
                                    sql.one(
                                            "SELECT c.id,c.document_id FROM index_task t JOIN"
                                                    + " knowledge_chunk c ON c.id=t.chunk_id WHERE"
                                                    + " t.id=?",
                                            taskId);
                            if (reference == null) {
                                return false;
                            }
                            // 与资料更新保持同一锁顺序：文档、片段、任务，避免互相等待。
                            sql.one(
                                    "SELECT id FROM knowledge_document WHERE id=? FOR UPDATE",
                                    reference.get("document_id"));
                            sql.one(
                                    "SELECT id FROM knowledge_chunk WHERE id=? FOR UPDATE",
                                    reference.get("id"));
                            var task =
                                    sql.one(
                                            """
SELECT * FROM index_task WHERE id=? AND claim_token=? AND status='PROCESSING'
AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE
""",
                                            taskId,
                                            token);
                            if (task == null || !hash.equals(text(task, "content_hash"))) {
                                return false;
                            }
                            var chunk =
                                    sql.one(
                                            "SELECT c.*,d.enabled AS document_enabled FROM"
                                                + " knowledge_chunk c JOIN knowledge_document d ON"
                                                + " d.id=c.document_id WHERE c.id=? FOR UPDATE",
                                            task.get("chunk_id"));
                            if (chunk == null || !hash.equals(text(chunk, "content_hash"))) {
                                return false;
                            }
                            long chunkId = number(task, "chunk_id");
                            if ("DELETE".equals(text(task, "operation"))
                                    || !bool(chunk, "enabled")
                                    || !bool(chunk, "document_enabled")) {
                                sql.update("DELETE FROM chunk_embedding WHERE chunk_id=?", chunkId);
                            } else {
                                if (vector == null || vector.length != AiGateway.DIMENSION) {
                                    throw new IllegalArgumentException("Wrong vector dimensions");
                                }
                                sql.update(
                                        """
INSERT INTO chunk_embedding(chunk_id,model_key,dimension,content_hash,vector) VALUES(?,?,?,?,?)
ON DUPLICATE KEY UPDATE content_hash=VALUES(content_hash),vector=VALUES(vector),dimension=VALUES(dimension)
""",
                                        chunkId,
                                        AiGateway.MODEL_KEY,
                                        vector.length,
                                        hash,
                                        encode(normalize(vector)));
                            }
                            sql.update(
                                    "UPDATE index_task SET"
                                        + " status='DONE',claim_token=NULL,lease_until=NULL,last_error=NULL"
                                        + " WHERE id=?",
                                    taskId);
                            return true;
                        });
        if (Boolean.TRUE.equals(result)) {
            var current =
                    sql.one(
                            "SELECT e.* FROM index_task t LEFT JOIN chunk_embedding e ON"
                                    + " e.chunk_id=t.chunk_id AND e.model_key=? WHERE t.id=?",
                            AiGateway.MODEL_KEY,
                            taskId);
            var task = sql.one("SELECT chunk_id FROM index_task WHERE id=?", taskId);
            long chunkId = number(task, "chunk_id");
            if (current == null || current.get("vector") == null) {
                vectors.remove(chunkId);
            } else {
                vectors.put(
                        chunkId,
                        new Entry(
                                chunkId,
                                text(current, "content_hash"),
                                decode((byte[]) current.get("vector"))));
            }
        }
        return Boolean.TRUE.equals(result);
    }

    /** 在内存中排序，再回库逐一过滤失效内容和权限；绝不把旧向量当库存事实。 */
    public List<ScoredChunk> search(CurrentUser actor, float[] query, int limit) {
        if (!ready) {
            return List.of();
        }
        float[] normalized = normalize(query);
        List<Map.Entry<Entry, Double>> sorted =
                vectors.values().stream()
                        .map(entry -> Map.entry(entry, dot(normalized, entry.vector())))
                        .sorted(
                                Map.Entry.<Entry, Double>comparingByValue()
                                        .reversed()
                                        .thenComparingLong(entry -> entry.getKey().chunkId()))
                        .toList();
        List<ScoredChunk> result = new ArrayList<>();
        for (var candidate : sorted) {
            Entry entry = candidate.getKey();
            var valid = knowledge.validChunk(actor, entry.chunkId(), entry.hash());
            if (valid != null && valid.get("edition_id") != null) {
                result.add(
                        new ScoredChunk(
                                entry.chunkId(),
                                number(valid, "edition_id"),
                                candidate.getValue()));
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public IndexStatus status() {
        return new IndexStatus(
                ready,
                vectors.size(),
                sql.count(
                        "SELECT COUNT(*) FROM index_task WHERE status IN ('PENDING','PROCESSING')"),
                sql.count("SELECT COUNT(*) FROM index_task WHERE status='FAILED'"),
                sql.count(
                        "SELECT COUNT(*) FROM chunk_embedding e JOIN knowledge_chunk c ON"
                                + " c.id=e.chunk_id WHERE e.content_hash<>c.content_hash"),
                AiGateway.MODEL_KEY,
                workerEnabled);
    }

    public static float[] normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite vector");
            }
            sum += value * (double) value;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("Zero vector");
        }
        float[] result = vector.clone();
        float scale = (float) (1 / Math.sqrt(sum));
        for (int i = 0; i < result.length; i++) {
            result[i] *= scale;
        }
        return result;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions differ");
        }
        double score = 0;
        for (int i = 0; i < a.length; i++) {
            score += a[i] * (double) b[i];
        }
        return score;
    }

    public static byte[] encode(float[] vector) {
        ByteBuffer buffer =
                ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] decode(byte[] bytes) {
        if (bytes.length != AiGateway.DIMENSION * Float.BYTES) {
            throw new IllegalStateException("Stored vector dimension mismatch");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[AiGateway.DIMENSION];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }
}
