package com.magent.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.magent.server.entity.KnowledgeDoc;
import com.magent.server.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 知识库检索：BM25-lite 关键词匹配（纯 Java，无向量库/embedding 依赖）。
 * 中文按单字、英文/数字按词切分；文档分块后逐块打分，返回拼接的高分块。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final int CHUNK_SIZE = 500;

    private final KnowledgeDocMapper knowledgeDocMapper;

    /** 按查询检索相关片段，返回拼接文本（受 topK 与 maxChars 双重限制；无命中返回空串）。 */
    public String retrieve(String query, int topK, int maxChars) {
        List<KnowledgeDoc> docs = knowledgeDocMapper.selectList(null);
        if (docs.isEmpty()) {
            return "";
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return "";
        }

        List<Scored> scored = new ArrayList<>();
        for (KnowledgeDoc doc : docs) {
            for (String chunk : chunk(doc.getContent())) {
                double s = score(queryTerms, chunk);
                if (s > 0) {
                    scored.add(new Scored(s, doc.getName(), chunk));
                }
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        if (scored.isEmpty()) {
            return "";
        }
        // 相关度阈值：仅保留达到最高分 30% 的块，滤掉单字弱命中的无关文档
        double threshold = scored.get(0).score * 0.3;

        StringBuilder sb = new StringBuilder();
        int used = 0;
        int taken = 0;
        for (Scored s : scored) {
            if (taken >= topK || s.score < threshold) {
                break;
            }
            taken++;
            String piece = "【" + s.name + "】" + s.text;
            if (used + piece.length() > maxChars) {
                piece = piece.substring(0, Math.max(0, maxChars - used));
            }
            if (sb.length() > 0 && !piece.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(piece);
            used = sb.length();
            if (used >= maxChars) {
                break;
            }
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    private List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null) {
            return chunks;
        }
        for (int i = 0; i < content.length(); i += CHUNK_SIZE) {
            chunks.add(content.substring(i, Math.min(content.length(), i + CHUNK_SIZE)));
        }
        return chunks;
    }

    /** 词频加权打分：命中越多、稀有词权重越高（简化 BM25 的 tf 分量）。 */
    private double score(List<String> queryTerms, String chunk) {
        Map<String, Integer> tf = termFreq(chunk);
        double total = 0;
        for (String term : queryTerms) {
            Integer f = tf.get(term);
            if (f != null) {
                total += f / (f + 1.5);   // tf 饱和，避免长块刷分
            }
        }
        return total;
    }

    private Map<String, Integer> termFreq(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokenize(text)) {
            tf.merge(t, 1, Integer::sum);
        }
        return tf;
    }

    /** 中文逐字、英文/数字整词；忽略过短的停用符。 */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null) {
            return terms;
        }
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (word.length() > 0) {
                    terms.add(word.toString().toLowerCase());
                    word.setLength(0);
                }
                terms.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else {
                if (word.length() > 0) {
                    terms.add(word.toString().toLowerCase());
                    word.setLength(0);
                }
            }
        }
        if (word.length() > 0) {
            terms.add(word.toString().toLowerCase());
        }
        return terms;
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private record Scored(double score, String name, String text) {
    }
}
