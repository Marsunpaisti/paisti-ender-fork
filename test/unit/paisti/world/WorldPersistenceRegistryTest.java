package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.storage.FileStorageBackend;
import paisti.world.storage.StorageBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorldPersistenceRegistryTest {
    @Test
    @Tag("unit")
    void sameGenusReturnsSameInstance() throws IOException {
        Path basePath = Files.createTempDirectory("world-persistence-registry-same-test");
        try (WorldPersistenceRegistry registry = new WorldPersistenceRegistry(basePath)) {
            WorldPersistence first = registry.get("abc123");
            WorldPersistence second = registry.get("abc123");

            assertSame(first, second);
        } finally {
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void differentGenusReturnsDifferentInstances() throws IOException {
        Path basePath = Files.createTempDirectory("world-persistence-registry-different-test");
        try (WorldPersistenceRegistry registry = new WorldPersistenceRegistry(basePath)) {
            WorldPersistence first = registry.get("abc123");
            WorldPersistence second = registry.get("def456");

            assertNotSame(first, second);
        } finally {
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void storagePathIsSanitizedByGenus() throws IOException {
        Path basePath = Files.createTempDirectory("world-persistence-registry-path-test");
        try {
            assertEquals(basePath.resolve("abc_123"), WorldPersistenceRegistry.storageBasePath(basePath, "abc/123"));
            assertEquals(basePath.resolve("unknown"), WorldPersistenceRegistry.storageBasePath(basePath, null));
            assertEquals(basePath.resolve("unknown"), WorldPersistenceRegistry.storageBasePath(basePath, ""));
            assertEquals(basePath.resolve("abc-DEF_123.45"), WorldPersistenceRegistry.storageBasePath(basePath, "abc-DEF_123.45"));
            assertEquals(Path.of("base", "unknown"), WorldPersistenceRegistry.storageBasePath(Path.of("base"), "."));
            assertEquals(Path.of("base", "unknown"), WorldPersistenceRegistry.storageBasePath(Path.of("base"), ".."));
        } finally {
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void createdPersistenceLoadsExistingChunksFromGenusPath() throws IOException {
        Path basePath = Files.createTempDirectory("world-persistence-registry-load-test");
        try {
            long gridId = 101L;
            ChunkData chunk = new ChunkData(gridId, 202L, Coord.of(3, 4));
            chunk.setCellFlags(5, 6, WorldMapConstants.CELL_BLOCKED_TERRAIN);
            try (StorageBackend backend = new FileStorageBackend(WorldPersistenceRegistry.storageBasePath(basePath, "abc123"))) {
                backend.saveChunk(chunk);
                backend.flush();
            }

            try (WorldPersistenceRegistry registry = new WorldPersistenceRegistry(basePath)) {
                WorldPersistence persistence = registry.get("abc123");

                assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, persistence.worldMap().getCellFlags(gridId, 5, 6));
            }
        } finally {
            deleteTree(basePath);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if(!Files.exists(root))
            return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch(IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch(RuntimeException e) {
            if(e.getCause() instanceof IOException)
                throw (IOException) e.getCause();
            throw e;
        }
    }
}
