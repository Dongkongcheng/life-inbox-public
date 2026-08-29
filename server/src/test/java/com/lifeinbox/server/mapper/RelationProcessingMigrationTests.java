package com.lifeinbox.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationProcessingMigrationTests {

    private static final List<String> COLUMNS = List.of(
            "relation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PROCESSED'",
            "relation_attempt_id VARCHAR(36) NULL",
            "relation_error_message VARCHAR(255) NULL",
            "relation_started_time DATETIME NULL",
            "relation_finished_time DATETIME NULL"
    );

    @Test
    void incrementalAndFreshSchemasContainTheSameNarrowLifecycleColumns() throws IOException {
        String incremental = normalize(Files.readString(sqlPath(
                "v0.5-task7-add-relation-processing-state.sql"
        )));
        String fresh = normalize(Files.readString(sqlPath("v0.5-schema.sql")));

        for (String column : COLUMNS) {
            assertTrue(incremental.contains(column));
            assertTrue(fresh.contains(column));
        }
        assertFalse(incremental.contains("UPDATE inbox_item"));
        assertFalse(incremental.contains("INSERT INTO"));
        assertFalse(incremental.contains("CREATE TABLE relation_candidate"));
    }

    private Path sqlPath(String filename) {
        Path fromServer = Path.of("..", "docs", "sql", filename);
        return Files.exists(fromServer) ? fromServer : Path.of("docs", "sql", filename);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
