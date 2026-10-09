package com.ooohyg.agent.testutil;

import com.ooohyg.agent.core.capability.retrieval.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 【测试用途】基于字符串哈希的确定性 EmbeddingClient 实现。
 *
 * <p>固定输出 16 维向量；相同文本 → 相同向量（确定性）；不同文本大概率
 * 产生不同向量，可满足检索链路（InMemoryVectorStore 余弦相似度）的功能验证。
 * 不接真实嵌入模型。
 *
 * <p>线程安全（无实例可变状态）。
 */
public final class HashEmbeddingClient implements EmbeddingClient {

    private static final int DIM = 16;

    @Override
    public List<Float> embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        List<Float> vector = new ArrayList<>(DIM);
        for (int i = 0; i < DIM; i++) {
            long h = hash(text, i);
            float v = (h % 2001) / 1000.0f - 1.0f; // 归一化到 [-1, 1]
            vector.add(v);
        }
        return List.copyOf(vector);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be empty");
        }
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (String t : texts) {
            result.add(embed(t));
        }
        return List.copyOf(result);
    }

    private static long hash(String text, int seed) {
        long h = 1125899906842597L;
        String input = seed + "|" + text;
        for (int i = 0; i < input.length(); i++) {
            h = 31 * h + input.charAt(i);
        }
        return h;
    }
}
