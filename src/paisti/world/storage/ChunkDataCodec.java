package paisti.world.storage;

import haven.Coord;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;
import paisti.world.WorldMapConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.util.List;

public final class ChunkDataCodec {
    public static final int FORMAT_VERSION = 1;

    private ChunkDataCodec() {
    }

    public static void write(OutputStream output, ChunkData chunk) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        out.writeInt(FORMAT_VERSION);
        out.writeLong(chunk.gridId);
        out.writeLong(chunk.segmentId);
        out.writeInt(chunk.chunkCoord.x);
        out.writeInt(chunk.chunkCoord.y);
        out.writeUTF(chunk.layer);
        out.writeLong(chunk.lastUpdated);
        out.writeLong(chunk.version);
        out.write(chunk.cells);

        int portalCount = 0;
        for (List<Portal> portals : chunk.portalsByCell.values()) {
            portalCount += portals.size();
        }
        out.writeInt(portalCount);
        for (List<Portal> portals : chunk.portalsByCell.values()) {
            for (Portal portal : portals) {
                out.writeUTF(portal.type.name());
                out.writeInt(portal.sourceLocalCell.x);
                out.writeInt(portal.sourceLocalCell.y);
                out.writeLong(portal.targetChunkId);
                out.writeInt(portal.targetCellX);
                out.writeInt(portal.targetCellY);
            }
        }
        out.flush();
    }

    public static ChunkData read(InputStream input) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(input));
            int formatVersion = in.readInt();
            if (formatVersion != FORMAT_VERSION) {
                throw new ChunkDataCorruptionException("unsupported chunk data format version: " + formatVersion);
            }

            ChunkData chunk = new ChunkData(in.readLong(), in.readLong(), Coord.of(in.readInt(), in.readInt()));
            chunk.layer = in.readUTF();
            chunk.lastUpdated = in.readLong();
            chunk.version = in.readLong();
            in.readFully(chunk.cells, 0, WorldMapConstants.CELL_COUNT);

            int portalCount = in.readInt();
            if (portalCount < 0) {
                throw new ChunkDataCorruptionException("negative portal count: " + portalCount);
            }
            if (portalCount > WorldMapConstants.CELL_COUNT) {
                throw new ChunkDataCorruptionException("implausible portal count: " + portalCount);
            }
            for (int i = 0; i < portalCount; i++) {
                try {
                    Portal portal = new Portal(
                            readPortalType(in),
                            Coord.of(in.readInt(), in.readInt()),
                            in.readLong(),
                            in.readInt(),
                            in.readInt());
                    chunk.addPortal(portal);
                } catch (IllegalArgumentException e) {
                    throw new ChunkDataCorruptionException("invalid portal coordinates", e);
                }
            }
            chunk.dirty = false;
            return chunk;
        } catch (EOFException | UTFDataFormatException e) {
            throw new ChunkDataCorruptionException("truncated or malformed chunk payload", e);
        }
    }

    private static PortalType readPortalType(DataInputStream in) throws IOException {
        String encodedPortalType = in.readUTF();
        try {
            return PortalType.valueOf(encodedPortalType);
        } catch (IllegalArgumentException e) {
            throw new ChunkDataCorruptionException("invalid portal type: " + encodedPortalType, e);
        }
    }
}
