package com.lifeinbox.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCandidateMigrationTests {

    @Test
    void freshV04SchemaContainsTheExactIncrementalActionTable() throws IOException {
        String incremental = Files.readString(sqlPath("v0.4-task2-add-action-candidate.sql"));
        String fresh = Files.readString(sqlPath("v0.4-schema.sql"));
        String v03 = Files.readString(sqlPath("v0.3-schema.sql"));

        String incrementalTable = actionTable(incremental);
        String freshTable = actionTable(fresh);
        assertEquals(incrementalTable, freshTable);
        assertFalse(v03.contains("CREATE TABLE action_candidate"));
        assertTrue(incrementalTable.contains(
                "KEY idx_action_candidate_item_status (inbox_item_id, status)"
        ));
        assertTrue(incrementalTable.contains(
                "FOREIGN KEY (inbox_item_id) REFERENCES inbox_item (id) ON DELETE CASCADE"
        ));
    }

    private Path sqlPath(String filename) {
        Path fromServer = Path.of("..", "docs", "sql", filename);
        return Files.exists(fromServer) ? fromServer : Path.of("docs", "sql", filename);
    }

    private String actionTable(String sql) {
        int start = sql.indexOf("CREATE TABLE action_candidate");
        int end = sql.indexOf(';', start);
        assertTrue(start >= 0 && end > start);
        return sql.substring(start, end + 1);
    }
}
