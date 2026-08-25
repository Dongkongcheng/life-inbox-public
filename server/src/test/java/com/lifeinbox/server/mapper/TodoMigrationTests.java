package com.lifeinbox.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoMigrationTests {

    @Test
    void freshV04SchemaContainsTheExactIncrementalTodoTable() throws IOException {
        String incremental = Files.readString(sqlPath("v0.4-task4-add-todo.sql"));
        String fresh = Files.readString(sqlPath("v0.4-schema.sql"));

        assertEquals(todoTable(incremental), todoTable(fresh));
    }

    @Test
    void todoSchemaKeepsBusinessStateIndependentAndSourceLinksOptional() throws IOException {
        String table = todoTable(Files.readString(sqlPath("v0.4-task4-add-todo.sql")));

        assertTrue(table.contains("source_inbox_item_id BIGINT NULL"));
        assertTrue(table.contains("source_action_candidate_id BIGINT NULL"));
        assertTrue(table.contains("title VARCHAR(255) NOT NULL"));
        assertTrue(table.contains("description TEXT NULL"));
        assertTrue(table.contains("status VARCHAR(20) NOT NULL DEFAULT 'OPEN'"));
        assertTrue(table.contains("due_date DATE NULL"));
        assertTrue(table.contains("completed_time DATETIME NULL"));
        assertFalse(table.contains("action_type"));
        assertFalse(table.contains("deadline_text"));
        assertFalse(table.contains("evidence"));
        assertFalse(table.contains("due_time"));
    }

    @Test
    void candidateIsUniqueAndDeletingEitherSourceOnlyClearsTraceability() throws IOException {
        String table = todoTable(Files.readString(sqlPath("v0.4-task4-add-todo.sql")));

        assertTrue(table.contains(
                "UNIQUE KEY uk_todo_source_action_candidate (source_action_candidate_id)"
        ));
        assertTrue(table.contains(
                "FOREIGN KEY (source_inbox_item_id) REFERENCES inbox_item (id) ON DELETE SET NULL"
        ));
        assertTrue(table.contains(
                "FOREIGN KEY (source_action_candidate_id) REFERENCES action_candidate (id) "
                        + "ON DELETE SET NULL"
        ));
        assertFalse(table.contains("ON DELETE CASCADE"));
    }

    @Test
    void historicalSchemasAndTask2MigrationRemainWithoutTodo() throws IOException {
        assertFalse(Files.readString(sqlPath("v0.3-schema.sql")).contains("CREATE TABLE todo"));
        assertFalse(Files.readString(sqlPath("v0.4-task2-add-action-candidate.sql"))
                .contains("CREATE TABLE todo"));
    }

    private Path sqlPath(String filename) {
        Path fromServer = Path.of("..", "docs", "sql", filename);
        return Files.exists(fromServer) ? fromServer : Path.of("docs", "sql", filename);
    }

    private String todoTable(String sql) {
        int start = sql.indexOf("CREATE TABLE todo");
        int end = sql.indexOf(';', start);
        assertTrue(start >= 0 && end > start);
        return sql.substring(start, end + 1);
    }
}
