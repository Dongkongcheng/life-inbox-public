package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只按两个检索列表中的名次执行 RRF，不解释或混加底层异构 Score。 */
final class HybridSearchFusion {

    // RRF_K 是排名贡献的平滑参数，不是业务相关性阈值。
    static final int RRF_K = 60;
    private static final int MISSING_RANK = Integer.MAX_VALUE;

    private HybridSearchFusion() {
    }

    static List<InboxItem> fuse(
            List<InboxItem> keywordItems,
            List<InboxItem> semanticItems,
            int limit
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException("Hybrid result limit 必须大于 0");
        }

        Map<Long, FusedCandidate> candidates = new LinkedHashMap<>();
        addBranch(candidates, keywordItems, true);
        addBranch(candidates, semanticItems, false);

        return candidates.values().stream()
                .sorted(Comparator
                        .comparingDouble(FusedCandidate::score).reversed()
                        .thenComparingInt(FusedCandidate::bestRank)
                        // RRF 同分时优先保持 Keyword 次序，避免精准标题命中被任意打散。
                        .thenComparingInt(FusedCandidate::keywordRank)
                        .thenComparingInt(FusedCandidate::semanticRank)
                        .thenComparingLong(candidate -> candidate.item().getId()))
                .limit(limit)
                .map(FusedCandidate::item)
                .toList();
    }

    static double reciprocalRankContribution(int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("RRF rank 必须从 1 开始");
        }
        return 1.0d / (RRF_K + rank);
    }

    private static void addBranch(
            Map<Long, FusedCandidate> candidates,
            List<InboxItem> items,
            boolean keywordBranch
    ) {
        Set<Long> seenIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            InboxItem item = items.get(index);
            if (item == null || item.getId() == null || !seenIds.add(item.getId())) {
                continue;
            }

            int rank = index + 1;
            FusedCandidate candidate = candidates.computeIfAbsent(
                    item.getId(),
                    ignored -> new FusedCandidate(item)
            );
            candidate.addRank(rank, keywordBranch);
        }
    }

    private static final class FusedCandidate {

        private final InboxItem item;
        private double score;
        private int keywordRank = MISSING_RANK;
        private int semanticRank = MISSING_RANK;

        private FusedCandidate(InboxItem item) {
            this.item = item;
        }

        private void addRank(int rank, boolean keywordBranch) {
            score += reciprocalRankContribution(rank);
            if (keywordBranch) {
                keywordRank = rank;
            } else {
                semanticRank = rank;
            }
        }

        private InboxItem item() {
            return item;
        }

        private double score() {
            return score;
        }

        private int keywordRank() {
            return keywordRank;
        }

        private int semanticRank() {
            return semanticRank;
        }

        private int bestRank() {
            return Math.min(keywordRank, semanticRank);
        }
    }
}
