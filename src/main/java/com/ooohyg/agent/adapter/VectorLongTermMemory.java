package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.capability.memory.LongMemoryRecord;
import com.ooohyg.agent.core.capability.memory.LongMemorySearchResult;
import com.ooohyg.agent.core.capability.memory.LongTermMemory;
import com.ooohyg.agent.core.capability.retrieval.Document;
import com.ooohyg.agent.core.capability.retrieval.EmbeddingClient;
import com.ooohyg.agent.core.capability.retrieval.SearchResult;
import com.ooohyg.agent.core.capability.retrieval.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于向量存储的长期记忆。
 *
 * <p>复用 {@link EmbeddingClient} + {@link VectorStore} 基础设施：
 * {@code remember} 时把记忆内容嵌入并写入向量库（同 id 覆盖）；
 * {@code recall} 时把查询嵌入并在向量库中检索，按相关度返回前 K 条。
 *
 * <p>记忆语义与 RAG 文档语义的差异由上层承载：本类只负责
 * "以向量方式存一段文本、以向量方式按相关度取回"，与检索完全同构。
 */
public final class VectorLongTermMemory implements LongTermMemory {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    /**
     * @param embeddingClient 嵌入客户端，非 null
     * @param vectorStore     向量存储，非 null
     */
    public VectorLongTermMemory(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient,
                "embeddingClient must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore,
                "vectorStore must not be null");
    }

    @Override
    public void remember(LongMemoryRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        List<Float> vector = embeddingClient.embed(record.content());
        Document doc = new Document(record.id(), record.content(), record.metadata());
        vectorStore.add(List.of(doc), List.of(vector));
    }

    @Override
    public List<LongMemorySearchResult> recall(String query, int topK) {
        Objects.requireNonNull(query, "query must not be null");
        List<Float> queryVector = embeddingClient.embed(query);
        List<SearchResult> results = vectorStore.search(queryVector, topK);

        List<LongMemorySearchResult> out = new ArrayList<>(results.size());
        for (SearchResult sr : results) {
            LongMemoryRecord record = new LongMemoryRecord(
                    sr.document().id(),
                    sr.document().content(),
                    sr.document().metadata());
            out.add(new LongMemorySearchResult(record, sr.score()));
        }
        return out;
    }
}
