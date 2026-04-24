package paisti.world.storage;

import haven.Warning;
import paisti.world.ChunkData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class FileStorageBackend implements StorageBackend {
    private final Path chunksPath;

    public FileStorageBackend(Path basePath) {
        this.chunksPath = Objects.requireNonNull(basePath, "basePath").resolve("chunks");
    }

    @Override
    public Collection<ChunkData> loadChunks() throws IOException {
        if (!Files.isDirectory(chunksPath)) {
            return List.of();
        }

        List<ChunkData> chunks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunksPath, "*.bin")) {
            for (Path path : stream) {
                try (InputStream input = Files.newInputStream(path)) {
                    try {
                        ChunkData chunk = ChunkDataCodec.read(input);
                        long expectedGridId = parseGridId(path.getFileName().toString());
                        if (chunk.gridId != expectedGridId) {
                            throw new ChunkDataCorruptionException("gridId " + Long.toUnsignedString(chunk.gridId) + " does not match filename");
                        }
                        chunks.add(chunk);
                    } catch (ChunkDataCorruptionException e) {
                        Warning.warn("failed to load chunk file %s: %s", path.getFileName(), e.getMessage());
                    }
                }
            }
        }
        return chunks;
    }

    @Override
    public void saveChunk(ChunkData chunk) throws IOException {
        ChunkData safeChunk = Objects.requireNonNull(chunk, "chunk");
        Files.createDirectories(chunksPath);

        Path finalPath = chunkPath(safeChunk.gridId, ".bin");
        Path tempPath = chunkPath(safeChunk.gridId, ".bin.tmp");
        try (OutputStream output = Files.newOutputStream(tempPath)) {
            ChunkDataCodec.write(output, safeChunk);
        }
        Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    private Path chunkPath(long gridId, String suffix) {
        return chunksPath.resolve(Long.toUnsignedString(gridId) + suffix);
    }

    private static long parseGridId(String fileName) throws ChunkDataCorruptionException {
        String encodedGridId = fileName.substring(0, fileName.length() - 4);
        try {
            return Long.parseUnsignedLong(encodedGridId);
        } catch (NumberFormatException e) {
            throw new ChunkDataCorruptionException("invalid chunk filename: " + fileName, e);
        }
    }
}
