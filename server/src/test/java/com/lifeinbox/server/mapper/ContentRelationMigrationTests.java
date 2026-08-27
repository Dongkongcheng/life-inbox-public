package com.lifeinbox.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentRelationMigrationTests {

    private static final List<String> V04_TABLES = List.of(
            "inbox_item",
            "tag",
            "inbox_tag",
            "inbox_keyword",
            "inbox_entity",
            "action_candidate",
            "todo"
    );

    @Test
    void freshV05SchemaEqualsV04SchemaPlusTask1RelationTable() throws IOException {
        String v04 = Files.readString(sqlPath("v0.4-schema.sql"));
        String incremental = Files.readString(sqlPath("v0.5-task1-add-content-relation.sql"));
        String fresh = Files.readString(sqlPath("v0.5-schema.sql"));

        for (String tableName : V04_TABLES) {
            assertEquals(createTable(v04, tableName), createTable(fresh, tableName));
        }
        assertEquals(
                createTable(incremental, "content_relation"),
                createTable(fresh, "content_relation")
        );
        assertFalse(v04.contains("CREATE TABLE content_relation"));
    }

    @Test
    void relationTableHasCanonicalPairUniquenessEndpointIndexesAndCascadeFks()
            throws IOException {
        String table = normalize(createTable(
                Files.readString(sqlPath("v0.5-task1-add-content-relation.sql")),
                "content_relation"
        ));

        assertTrue(table.contains("id BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(table.contains("left_inbox_item_id BIGINT NOT NULL"));
        assertTrue(table.contains("right_inbox_item_id BIGINT NOT NULL"));
        assertTrue(table.contains("relation_type VARCHAR(32) NOT NULL"));
        assertTrue(table.contains("PRIMARY KEY (id)"));
        assertTrue(table.contains("KEY idx_content_relation_left (left_inbox_item_id)"));
        assertTrue(table.contains("KEY idx_content_relation_right (right_inbox_item_id)"));
        assertTrue(table.contains(
                "UNIQUE KEY uk_content_relation_pair_type "
                        + "(left_inbox_item_id, right_inbox_item_id, relation_type)"
        ));
        assertTrue(table.contains(
                "FOREIGN KEY (left_inbox_item_id) REFERENCES inbox_item (id) ON DELETE CASCADE"
        ));
        assertTrue(table.contains(
                "FOREIGN KEY (right_inbox_item_id) REFERENCES inbox_item (id) ON DELETE CASCADE"
        ));
    }

    @Test
    void firstVersionSchemaDoesNotPersistCandidateScoreEvidenceOrProcessingState()
            throws IOException {
        String incremental = Files.readString(sqlPath("v0.5-task1-add-content-relation.sql"));
        String table = createTable(incremental, "content_relation");

        assertFalse(incremental.contains("CREATE TABLE relation_candidate"));
        assertFalse(table.contains("score"));
        assertFalse(table.contains("evidence"));
        assertFalse(table.contains("reason"));
        assertFalse(table.contains("provider"));
        assertFalse(table.contains("attempt"));
        assertFalse(table.contains("status"));
    }

    private Path sqlPath(String filename) {
        Path fromServer = Path.of("..", "docs", "sql", filename);
        return Files.exists(fromServer) ? fromServer : Path.of("docs", "sql", filename);
    }

    private String createTable(String sql, String tableName) {
        int start = sql.indexOf("CREATE TABLE " + tableName);
        int end = sql.indexOf(';', start);
        assertTrue(start >= 0 && end > start, "missing table: " + tableName);
        return sql.substring(start, end + 1);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
