package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridSearchFusionTests {

    @Test
    void reciprocalRankContributionUsesCentralRrfKAndOneBasedRank() {
        assertEquals(
                1.0d / (HybridSearchFusion.RRF_K + 1),
                HybridSearchFusion.reciprocalRankContribution(1)
        );
        assertEquals(
                1.0d / (HybridSearchFusion.RRF_K + 3),
                HybridSearchFusion.reciprocalRankContribution(3)
        );
    }

    @Test
    void itemPresentInBothBranchesReceivesBothContributionsAndAppearsOnce() {
        InboxItem a = item(1L);
        InboxItem b = item(2L);
        InboxItem c = item(3L);
        InboxItem d = item(4L);
        InboxItem e = item(5L);

        List<InboxItem> result = HybridSearchFusion.fuse(
                List.of(a, b, c),
                List.of(a, d, e),
                10
        );

        assertEquals(List.of(1L, 2L, 4L, 3L, 5L), ids(result));
        assertEquals(1, result.stream().filter(item -> item.getId().equals(1L)).count());
    }

    @Test
    void orderingFollowsActualRrfFormulaInsteadOfEitherRawScoreScale() {
        InboxItem a = item(1L);
        InboxItem b = item(2L);
        InboxItem c = item(3L);
        InboxItem d = item(4L);

        // C = 1/(60+3) + 1/(60+1)，略高于 B 的两次 rank 2 contribution。
        List<InboxItem> result = HybridSearchFusion.fuse(
                List.of(a, b, c),
                List.of(c, b, d),
                10
        );

        assertEquals(List.of(3L, 2L, 1L, 4L), ids(result));
    }

    @Test
    void preservesKeywordOnlyAndSemanticOnlyItemsAndAppliesFinalLimit() {
        InboxItem a = item(1L);
        InboxItem b = item(2L);
        InboxItem c = item(3L);
        InboxItem d = item(4L);

        assertEquals(
                List.of(1L, 2L),
                ids(HybridSearchFusion.fuse(List.of(a, b), List.of(), 10))
        );
        assertEquals(
                List.of(3L, 4L),
                ids(HybridSearchFusion.fuse(List.of(), List.of(c, d), 10))
        );
        assertEquals(
                List.of(1L, 3L, 2L),
                ids(HybridSearchFusion.fuse(List.of(a, b), List.of(c, d), 3))
        );
    }

    private InboxItem item(Long id) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setStatus("ACTIVE");
        return item;
    }

    private List<Long> ids(List<InboxItem> items) {
        return items.stream().map(InboxItem::getId).toList();
    }
}
