package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.capability.retrieval.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 确定性哈希嵌入客户端。
 *
 * <p>不接任何真实 Embedding 模型，用字符级哈希把文本映射到固定维度的
 * 向量。相同输入永远得到相同向量，语义相近的文本（共享字符/二元组）
 * 会得到更高的余弦相似度——足以支撑检索链路的端到端验证。
 *
 * <p><b>算法：</b>对每个字符及其与前一个字符组成的二元组做加权哈希，
 * 累加到 16 维向量，最后归一化。二元组让"顺序"参与计算，
 * 避免"向量检索"和"检索向量"得到完全相同的向量。
 *
 * <p><b>适用场景：</b>本地冒烟、CI 无网络环境、接入真实
 * Embedding 模型前的骨架验证。生产环境应替换为真实模型客户端。
 */
public final class HashEmbeddingClient implements EmbeddingClient {

    /** 向量维度。16 足够区分短文本，且占用极小。 */
    private static final int DIM = 16;

    @Override
    public List<Float> embed(String text) {
        Objects.requireNonNull(text, "text must not be null");

        double[] vec = new double[DIM];
        char prev = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charIdx = Math.floorMod(c * 31 + i, DIM);
            vec[charIdx] += 1.0;

            if (prev != 0) {
                int bigram = (prev * 131 + c) & 0xFFFF;
                int bigramIdx = Math.floorMod(bigram * 17 + i, DIM);
                vec[bigramIdx] += 0.5;
            }
            prev = c;
        }

        return normalize(vec);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    private static List<Float> normalize(double[] vec) {
        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        List<Float> out = new ArrayList<>(DIM);
        if (norm == 0.0) {
            for (int i = 0; i < DIM; i++) {
                out.add(0.0f);
            }
        } else {
            for (double v : vec) {
                out.add((float) (v / norm));
            }
        }
        return out;
    }
}
