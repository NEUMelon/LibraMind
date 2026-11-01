package com.libramind.search;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.ai.AiGateway;
import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.BookView;
import com.libramind.catalog.Isbn;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 关键词、向量与 RRF 检索，所有结果最终回到真实版本与实时馆藏。 */
@Service
public class SearchService {

    public record SearchHit(BookView book, double score, List<String> matchReasons) {}

    public record SearchResult(
            String query,
            String mode,
            List<SearchHit> hits,
            List<String> degraded,
            long latencyMs) {}

    private final SqlStore sql;
    private final CatalogDAO catalog;
    private final VectorIndexService vectors;
    private final AiGateway ai;

    public SearchService(
            SqlStore sql, CatalogDAO catalog, VectorIndexService vectors, AiGateway ai) {
        this.sql = sql;
        this.catalog = catalog;
        this.vectors = vectors;
        this.ai = ai;
    }

    public SearchResult search(
            CurrentUser actor, String query, String mode, int limit, boolean availableOnly) {
        long started = System.nanoTime();
        if (query == null
                || query.isBlank()
                || query.length() > 500
                || limit < 1
                || limit > 20
                || !List.of("keyword", "vector", "hybrid").contains(mode)) {
            throw BusinessException.invalid("查询不能为空且最多500个字符，limit在1到20之间");
        }
        String q = query.trim();
        if (q.replaceAll("[\\s-]", "").matches("[0-9Xx]{10,13}")) {
            try {
                BookView exact = catalog.findIsbn(Isbn.normalize(q));
                if (exact != null && (!availableOnly || exact.availableCount() > 0)) {
                    return new SearchResult(
                            q,
                            "isbn",
                            List.of(new SearchHit(exact, 1, List.of("ISBN精确匹配"))),
                            List.of(),
                            elapsed(started));
                }
                // 有效ISBN明确指定版本；找不到时不拿语义相似的其它书冒充匹配。
                return new SearchResult(q, "isbn", List.of(), List.of(), elapsed(started));
            } catch (BusinessException ignored) {
                // Non-ISBN numeric text can still be searched as an ordinary keyword.
            }
        }
        List<Long> keyword = keywordIds(q, 100);
        List<Long> semantic = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        if (!"keyword".equals(mode)) {
            if (actor == null || !vectors.status().ready() || vectors.status().loaded() == 0) {
                degraded.add("vector");
            } else {
                try {
                    var embedding = ai.embed(List.of(q), actor.id()).getFirst();
                    semantic =
                            vectors.search(actor, embedding, 100).stream()
                                    .map(VectorIndexService.ScoredChunk::editionId)
                                    .distinct()
                                    .toList();
                } catch (BusinessException exception) {
                    degraded.add("vector");
                }
            }
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        if (!"vector".equals(mode) || !degraded.isEmpty()) {
            addRanks(scores, keyword);
        }
        if (!"keyword".equals(mode) && degraded.isEmpty()) {
            addRanks(scores, semantic);
        }
        List<Long> semanticIds = semantic;
        List<SearchHit> hits =
                scores.entrySet().stream()
                        .sorted(
                                Map.Entry.<Long, Double>comparingByValue()
                                        .reversed()
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .map(
                                entry -> {
                                    BookView book = catalog.findBook(entry.getKey());
                                    List<String> reasons = new ArrayList<>();
                                    if (keyword.contains(entry.getKey())) {
                                        reasons.add("关键词匹配");
                                    }
                                    if (semanticIds.contains(entry.getKey())) {
                                        reasons.add("语义相关");
                                    }
                                    return book == null
                                            ? null
                                            : new SearchHit(book, entry.getValue(), reasons);
                                })
                        .filter(
                                hit ->
                                        hit != null
                                                && (!availableOnly
                                                        || hit.book().availableCount() > 0))
                        .limit(limit)
                        .toList();
        return new SearchResult(
                q, degraded.isEmpty() ? mode : "keyword", hits, degraded, elapsed(started));
    }

    /** 中文ngram全文检索与子串兜底并用，单字和英文书名也有确定性查找方式。 */
    public List<Long> keywordIds(String query, int limit) {
        String like = "%" + query + "%";
        return sql
                .list(
                        """
SELECT e.id,MATCH(title,authors,publisher,category,description) AGAINST(? IN NATURAL LANGUAGE MODE) AS relevance
FROM book_edition e WHERE e.archived=FALSE AND (
MATCH(title,authors,publisher,category,description) AGAINST(? IN NATURAL LANGUAGE MODE)>0
OR e.title LIKE ? OR e.authors LIKE ? OR e.publisher LIKE ? OR e.category LIKE ? OR e.isbn13=?)
ORDER BY (e.title=?) DESC,relevance DESC,e.id LIMIT ?
""",
                        query,
                        query,
                        like,
                        like,
                        like,
                        like,
                        query,
                        query,
                        limit)
                .stream()
                .map(row -> number(row, "id"))
                .toList();
    }

    /** 按排名累加 RRF 分数，不混用全文检索分数和向量相似度。 */
    public static void addRanks(Map<Long, Double> scores, List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (60 + i + 1), Double::sum);
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
