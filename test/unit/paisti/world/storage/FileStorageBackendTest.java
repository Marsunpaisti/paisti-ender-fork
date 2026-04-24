package paisti.world.storage;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageBackendTest {
    @Test
    @Tag("unit")
    void savesAndLoadsCanonicalChunks() throws IOException {
        Path basePath = Files.createTempDirectory("file-storage-backend-test");
        try {
            ChunkData chunk = new ChunkData(0x123456789ABCDEFL, 33L, Coord.of(10, 11));
            chunk.layer = "inside";
            chunk.lastUpdated = 4444L;
            chunk.version = 55L;
            chunk.setCellFlags(12, 13, 200);
            chunk.addPortal(new Portal(PortalType.CELLAR, Coord.of(12, 13), 999L, 14, 15));

            Path chunksDir = basePath.resolve("chunks");
            Files.createDirectories(chunksDir);
            Files.write(chunksDir.resolve("ignored.txt"), new byte[]{1, 2, 3});
            Files.write(chunksDir.resolve("ignored.bin.tmp"), new byte[]{4, 5, 6});

            try (StorageBackend backend = new FileStorageBackend(basePath)) {
                backend.saveChunk(chunk);
                backend.flush();
                assertFalse(chunk.dirty);
            }

            Path savedFile = chunksDir.resolve(Long.toUnsignedString(chunk.gridId) + ".bin");
            assertTrue(Files.exists(savedFile));
            assertFalse(Files.exists(chunksDir.resolve(Long.toUnsignedString(chunk.gridId) + ".bin.tmp")));

            Collection<ChunkData> loadedChunks;
            try (StorageBackend backend = new FileStorageBackend(basePath)) {
                loadedChunks = backend.loadChunks();
            }

            assertEquals(1, loadedChunks.size());
            ChunkData loaded = loadedChunks.iterator().next();
            List<Portal> portals = new ArrayList<>();
            loaded.getCellPortals(12, 13, portals);

            assertEquals(chunk.gridId, loaded.gridId);
            assertEquals(chunk.segmentId, loaded.segmentId);
            assertEquals(chunk.chunkCoord, loaded.chunkCoord);
            assertEquals(chunk.layer, loaded.layer);
            assertEquals(chunk.lastUpdated, loaded.lastUpdated);
            assertEquals(chunk.version, loaded.version);
            assertEquals(200, loaded.getCellFlags(12, 13));
            assertEquals(List.of(new Portal(PortalType.CELLAR, Coord.of(12, 13), 999L, 14, 15)), portals);
            assertFalse(loaded.dirty);
            assertNotNull(loaded.cells);
        } finally {
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void loadChunksSkipsCorruptChunkFilesAndLogsContext() throws IOException {
        Path basePath = Files.createTempDirectory("file-storage-backend-corrupt-test");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try {
            ChunkData validChunk = new ChunkData(101L, 202L, Coord.of(3, 4));
            validChunk.setCellFlags(5, 6, 77);

            Path chunksDir = basePath.resolve("chunks");
            Files.createDirectories(chunksDir);
            try (StorageBackend backend = new FileStorageBackend(basePath)) {
                backend.saveChunk(validChunk);
            }
            Files.write(chunksDir.resolve("broken.bin"), new byte[]{1, 2, 3});
            Files.write(chunksDir.resolve("bad-portal.bin"), corruptChunkBytesWithInvalidPortalType());

            Collection<ChunkData> loadedChunks;
            try (PrintStream captureErr = new PrintStream(errBytes, true)) {
                System.setErr(captureErr);
                try (StorageBackend backend = new FileStorageBackend(basePath)) {
                    loadedChunks = backend.loadChunks();
                }
            } finally {
                System.setErr(originalErr);
            }

            assertEquals(1, loadedChunks.size());
            ChunkData loaded = loadedChunks.iterator().next();
            assertEquals(validChunk.gridId, loaded.gridId);
            assertEquals(77, loaded.getCellFlags(5, 6));

            String warningText = errBytes.toString();
            assertTrue(warningText.contains("broken.bin"));
            assertTrue(warningText.contains("bad-portal.bin"));
            assertTrue(warningText.contains("failed to load chunk"));
        } finally {
            System.setErr(originalErr);
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void loadChunksPropagatesFilesystemErrors() throws IOException {
        Path basePath = Files.createTempDirectory("file-storage-backend-io-error-test");
        try {
            Path chunksDir = basePath.resolve("chunks");
            Files.createDirectories(chunksDir);
            Files.createDirectory(chunksDir.resolve("blocked.bin"));

            try (StorageBackend backend = new FileStorageBackend(basePath)) {
                IOException exception = assertThrows(IOException.class, backend::loadChunks);

                assertTrue(exception instanceof AccessDeniedException || exception.getClass() == IOException.class || exception.getClass().getName().endsWith("FileSystemException"));
            }
        } finally {
            deleteTree(basePath);
        }
    }

    @Test
    @Tag("unit")
    void loadChunksSkipsFilesWhoseGridIdDoesNotMatchFilename() throws IOException {
        Path basePath = Files.createTempDirectory("file-storage-backend-grid-mismatch-test");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try {
            ChunkData validChunk = new ChunkData(101L, 202L, Coord.of(3, 4));
            validChunk.setCellFlags(5, 6, 77);

            Path chunksDir = basePath.resolve("chunks");
            Files.createDirectories(chunksDir);
            try (StorageBackend backend = new FileStorageBackend(basePath)) {
                backend.saveChunk(validChunk);
            }

            ChunkData mismatchedChunk = new ChunkData(999L, 202L, Coord.of(7, 8));
            mismatchedChunk.setCellFlags(9, 10, 88);
            try (var output = Files.newOutputStream(chunksDir.resolve("102.bin"))) {
                ChunkDataCodec.write(output, mismatchedChunk);
            }

            Collection<ChunkData> loadedChunks;
            try (PrintStream captureErr = new PrintStream(errBytes, true)) {
                System.setErr(captureErr);
                try (StorageBackend backend = new FileStorageBackend(basePath)) {
                    loadedChunks = backend.loadChunks();
                }
            } finally {
                System.setErr(originalErr);
            }

            assertEquals(1, loadedChunks.size());
            ChunkData loaded = loadedChunks.iterator().next();
            assertEquals(validChunk.gridId, loaded.gridId);
            assertEquals(77, loaded.getCellFlags(5, 6));

            String warningText = errBytes.toString();
            assertTrue(warningText.contains("102.bin"));
            assertTrue(warningText.contains("gridId"));
        } finally {
            System.setErr(originalErr);
            deleteTree(basePath);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    private static byte[] corruptChunkBytesWithInvalidPortalType() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(ChunkDataCodec.FORMAT_VERSION);
            out.writeLong(1L);
            out.writeLong(2L);
            out.writeInt(3);
            out.writeInt(4);
            out.writeUTF("outside");
            out.writeLong(5L);
            out.writeLong(6L);
            out.write(new byte[40000]);
            out.writeInt(1);
            out.writeUTF("BROKEN_PORTAL_TYPE");
            out.writeInt(7);
            out.writeInt(8);
            out.writeLong(9L);
            out.writeInt(10);
            out.writeInt(11);
        }
        return bytes.toByteArray();
    }
}
