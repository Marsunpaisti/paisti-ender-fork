package paisti.world.storage;

import java.io.IOException;

public class ChunkDataCorruptionException extends IOException {
    public ChunkDataCorruptionException(String message) {
        super(message);
    }

    public ChunkDataCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
