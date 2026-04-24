package paisti.world.storage;

import paisti.world.ChunkData;

import java.io.IOException;
import java.util.Collection;

public interface StorageBackend extends AutoCloseable {
    Collection<ChunkData> loadChunks() throws IOException;

    void saveChunk(ChunkData chunk) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;
}
