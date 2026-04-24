package paisti.world;

import paisti.world.storage.FileStorageBackend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class WorldPersistenceRegistry implements AutoCloseable {
    private final Path basePath;
    private final Map<String, WorldPersistence> persistencesByGenus = new LinkedHashMap<>();

    public WorldPersistenceRegistry(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath");
    }

    public synchronized WorldPersistence get(String genus) throws IOException {
        String safeGenus = sanitizeGenus(genus);
        WorldPersistence existing = persistencesByGenus.get(safeGenus);
        if(existing != null)
            return existing;

        WorldPersistence created = createPersistence(safeGenus);
        persistencesByGenus.put(safeGenus, created);
        return created;
    }

    private WorldPersistence createPersistence(String safeGenus) throws IOException {
        WorldMap worldMap = new WorldMap(new FileStorageBackend(storageBasePath(basePath, safeGenus)));
        worldMap.load();
        return new WorldPersistence(worldMap);
    }

    static Path storageBasePath(Path basePath, String genus) {
        return basePath.resolve(sanitizeGenus(genus));
    }

    private static String sanitizeGenus(String genus) {
        if((genus == null) || genus.isEmpty())
            return "unknown";

        StringBuilder sanitized = new StringBuilder(genus.length());
        for(int i = 0; i < genus.length(); i++) {
            char ch = genus.charAt(i);
            if(((ch >= 'A') && (ch <= 'Z')) || ((ch >= 'a') && (ch <= 'z')) || ((ch >= '0') && (ch <= '9')) || (ch == '.') || (ch == '_') || (ch == '-'))
                sanitized.append(ch);
            else
                sanitized.append('_');
        }
        String sanitizedGenus = sanitized.toString();
        if(sanitizedGenus.equals(".") || sanitizedGenus.equals(".."))
            return "unknown";
        return sanitizedGenus;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException firstFailure = null;
        try {
            for(WorldPersistence persistence : persistencesByGenus.values()) {
                try {
                    persistence.close();
                } catch(IOException e) {
                    if(firstFailure == null)
                        firstFailure = e;
                    else
                        firstFailure.addSuppressed(e);
                }
            }
        } finally {
            persistencesByGenus.clear();
        }

        if(firstFailure != null)
            throw firstFailure;
    }
}
