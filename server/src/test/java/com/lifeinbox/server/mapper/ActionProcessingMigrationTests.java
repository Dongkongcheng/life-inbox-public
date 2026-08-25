package com.lifeinbox.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionProcessingMigrationTests {

    private static final List<String> ACTION_COLUMNS = List.of(
            "action_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PROCESSED'",
            "action_attempt_id VARCHAR(36) NULL",
            "action_error_message VARCHAR(255) NULL",
            "action_started_time DATETIME NULL",
            "action_finished_time DATETIME NULL"
    );

    @Test
    void incrementalAndFreshSchemasContainTheSameIndependentActionState() throws IOException {
        String incremental = Files.readString(
                sqlPath("v0.4-task7-add-action-processing-state.sql")
        );
        String fresh = Files.readString(sqlPath("v0.4-schema.sql"));

        for (String column : ACTION_COLUMNS) {
            assertTrue(incremental.contains(column));
            assertTrue(fresh.contains(column));
        }
        assertTrue(incremental.contains("ALTER TABLE inbox_item"));
        assertFalse(incremental.contains("MODIFY COLUMN ai_status"));
    }

    @Test
    void historicalSchemasAndMigrationsRemainUnchangedByActionLifecycle() throws IOException {
        assertFalse(Files.readString(sqlPath("v0.3-schema.sql")).contains("action_status"));
        assertFalse(Files.readString(sqlPath("v0.4-task2-add-action-candidate.sql"))
                .contains("action_status"));
        assertFalse(Files.readString(sqlPath("v0.4-task4-add-todo.sql"))
                .contains("action_status"));
    }

    private Path sqlPath(String filename) {
        Path fromServer = Path.of("..", "docs", "sql", filename);
        return Files.exists(fromServer) ? fromServer : Path.of("docs", "sql", filename);
    }
}
