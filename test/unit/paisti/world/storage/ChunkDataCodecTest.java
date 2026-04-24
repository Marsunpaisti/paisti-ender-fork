package paisti.world.storage;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;
import paisti.world.WorldMapConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkDataCodecTest {
    @Test
    @Tag("unit")
    void roundTripsCellsAndPortals() throws IOException {
        ChunkData original = new ChunkData(0x123456789ABCDEFL, 9001L, Coord.of(7, 8));
        original.layer = "cellar";
        original.lastUpdated = 123456789L;
        original.version = 77L;
        original.setCellFlags(1, 2, 3);
        original.setCellFlags(199, 198, 254);
        original.addPortal(new Portal(PortalType.DOOR, Coord.of(1, 2), 111L, 3, 4));
        original.addPortal(new Portal(PortalType.MINE, Coord.of(199, 198), 222L, 5, 6));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ChunkDataCodec.write(bytes, original);

        ChunkData decoded = ChunkDataCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        List<Portal> firstCellPortals = new ArrayList<>();
        List<Portal> secondCellPortals = new ArrayList<>();

        decoded.getCellPortals(1, 2, firstCellPortals);
        decoded.getCellPortals(199, 198, secondCellPortals);

        assertEquals(original.gridId, decoded.gridId);
        assertEquals(original.segmentId, decoded.segmentId);
        assertEquals(original.chunkCoord, decoded.chunkCoord);
        assertEquals(original.layer, decoded.layer);
        assertEquals(original.lastUpdated, decoded.lastUpdated);
        assertEquals(original.version, decoded.version);
        assertArrayEquals(original.cells, decoded.cells);
        assertEquals(List.of(new Portal(PortalType.DOOR, Coord.of(1, 2), 111L, 3, 4)), firstCellPortals);
        assertEquals(List.of(new Portal(PortalType.MINE, Coord.of(199, 198), 222L, 5, 6)), secondCellPortals);
        assertFalse(decoded.dirty);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            skipChunkHeader(in);
            assertEquals(2, in.readInt());
            assertEquals(PortalType.DOOR.name(), in.readUTF());
        }
    }

    @Test
    @Tag("unit")
    void writeDoesNotCloseCallerOwnedStream() throws IOException {
        TrackingOutputStream output = new TrackingOutputStream();

        ChunkDataCodec.write(output, new ChunkData(1L, 2L, Coord.of(3, 4)));

        assertFalse(output.closed);
        output.write(123);
    }

    @Test
    @Tag("unit")
    void readDoesNotCloseCallerOwnedStream() throws IOException {
        TrackingInputStream input = new TrackingInputStream(chunkBytesWithPortalMetadata(0, null));

        ChunkData chunk = ChunkDataCodec.read(input);

        assertEquals(1L, chunk.gridId);
        assertFalse(input.closed);
        assertEquals(-1, input.read());
    }

    @Test
    @Tag("unit")
    void rejectsUnsupportedFormatVersion() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(ChunkDataCodec.FORMAT_VERSION + 1);
            out.writeLong(1L);
            out.writeLong(2L);
            out.writeInt(3);
            out.writeInt(4);
            out.writeUTF("outside");
            out.writeLong(5L);
            out.writeLong(6L);
            out.write(new byte[WorldMapConstants.CELL_COUNT]);
            out.writeInt(0);
        }

        assertThrows(IOException.class, () -> ChunkDataCodec.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    @Tag("unit")
    void rejectsNegativePortalCount() throws IOException {
        IOException exception = assertThrows(IOException.class, () -> ChunkDataCodec.read(new ByteArrayInputStream(chunkBytesWithPortalMetadata(-1, null))));

        assertTrue(exception.getMessage().contains("negative portal count"));
    }

    @Test
    @Tag("unit")
    void rejectsOversizedPositivePortalCount() throws IOException {
        IOException exception = assertThrows(IOException.class, () -> ChunkDataCodec.read(new ByteArrayInputStream(chunkBytesWithPortalMetadata(WorldMapConstants.CELL_COUNT + 1, null))));

        assertTrue(exception instanceof ChunkDataCorruptionException);
        assertTrue(exception.getMessage().contains("portal count"));
    }

    @Test
    @Tag("unit")
    void rejectsInvalidPortalTypeValue() throws IOException {
        IOException exception = assertThrows(IOException.class, () -> ChunkDataCodec.read(new ByteArrayInputStream(chunkBytesWithPortalMetadata(1, "NOT_A_PORTAL"))));

        assertTrue(exception.getMessage().contains("invalid portal type"));
        assertTrue(exception.getMessage().contains("NOT_A_PORTAL"));
    }

    @Test
    @Tag("unit")
    void rejectsMalformedPortalCoordinatesAsCorruption() throws IOException {
        IOException exception = assertThrows(IOException.class, () -> ChunkDataCodec.read(new ByteArrayInputStream(chunkBytesWithPortalMetadata(1, PortalType.DOOR.name(), 200, 8))));

        assertTrue(exception instanceof ChunkDataCorruptionException);
        assertTrue(exception.getMessage().contains("invalid portal coordinates"));
    }

    private static byte[] chunkBytesWithPortalMetadata(int portalCount, String portalTypeName) throws IOException {
        return chunkBytesWithPortalMetadata(portalCount, portalTypeName, 7, 8);
    }

    private static byte[] chunkBytesWithPortalMetadata(int portalCount, String portalTypeName, int portalSourceX, int portalSourceY) throws IOException {
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
            out.write(new byte[WorldMapConstants.CELL_COUNT]);
            out.writeInt(portalCount);
            if (portalTypeName != null) {
                out.writeUTF(portalTypeName);
                out.writeInt(portalSourceX);
                out.writeInt(portalSourceY);
                out.writeLong(9L);
                out.writeInt(10);
                out.writeInt(11);
            }
        }

        return bytes.toByteArray();
    }

    private static void skipChunkHeader(DataInputStream in) throws IOException {
        in.readInt();
        in.readLong();
        in.readLong();
        in.readInt();
        in.readInt();
        in.readUTF();
        in.readLong();
        in.readLong();
        in.readFully(new byte[WorldMapConstants.CELL_COUNT]);
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] data) {
            this.delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
