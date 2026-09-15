package io.tinykv;

import io.tinykv.StorageException.StorageClosedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KVStoreTest {

    @Test
    @DisplayName("Basic CRUD lifecycle: put, get, overwrite, delete, and contains")
    void testBasicCrudLifecycle(@TempDir Path tempDir) throws IOException {
        try (KVStore store = KVStore.open(tempDir)) {
            store.put("user:1", "Alice");
            assertThat(store.count()).isEqualTo(1);
            assertThat(store.contains("user:1")).isTrue();
            assertThat(store.get("user:1")).contains("Alice");

            // Overwrite
            store.put("user:1", "Bob");
            assertThat(store.count()).isEqualTo(1);
            assertThat(store.get("user:1")).contains("Bob");

            // Delete
            assertThat(store.delete("user:1")).isTrue();
            assertThat(store.count()).isZero();
            assertThat(store.contains("user:1")).isFalse();
            assertThat(store.get("user:1")).isEmpty();

            // Delete non-existent
            assertThat(store.delete("user:1")).isFalse();
        }
    }

    @Test
    @DisplayName("Operations after store close throw StorageClosedException")
    void testClosedStoreRejectsOps(@TempDir Path tempDir) throws IOException {
        KVStore store = KVStore.open(tempDir);
        store.close();

        assertThatThrownBy(() -> store.put("k", "v")).isInstanceOf(StorageClosedException.class);
        assertThatThrownBy(() -> store.get("k")).isInstanceOf(StorageClosedException.class);
        assertThatThrownBy(() -> store.delete("k")).isInstanceOf(StorageClosedException.class);
        assertThatThrownBy(store::sync).isInstanceOf(StorageClosedException.class);
    }
}
