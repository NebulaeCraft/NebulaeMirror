package net.kuina.nebulaemirror.client;

import io.netty.buffer.Unpooled;
import net.kuina.nebulaemirror.writeback.ZoneOutlines;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneOutlineTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void payloadRoundTrip() {
        ZoneOutlines.Payload sent = new ZoneOutlines.Payload(List.of(
                new ZoneOutlines.Box(-30000000, 0, -17, 29999999, 255, 4, true),
                new ZoneOutlines.Box(1, 2, 3, 4, 5, 6, false)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ZoneOutlines.Payload.CODEC.encode(buf, sent);
        assertEquals(sent, ZoneOutlines.Payload.CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
        ZoneOutlines.Payload.CODEC.encode(buf, new ZoneOutlines.Payload(List.of()));
        assertTrue(ZoneOutlines.Payload.CODEC.decode(buf).zones().isEmpty(), "no zones: clears the client's outlines");
    }

    @Test
    void linesAreTheTwelveEdges() {
        // Blocks -3..0, 10..11, 5..5: the box is [-3, 1] x [10, 12] x [5, 6].
        List<ZoneOutlineRenderer.Line> lines = new ArrayList<>();
        ZoneOutlineRenderer.addLines(new ZoneOutlines.Box(-3, 10, 5, 0, 11, 5, true), lines);
        assertEquals(12, lines.size());
        assertEquals(12, lines.stream().distinct().count());
        double length = 0;
        for (ZoneOutlineRenderer.Line l : lines) {
            assertTrue(l.own());
            int changing = (l.x1() != l.x2() ? 1 : 0) + (l.y1() != l.y2() ? 1 : 0) + (l.z1() != l.z2() ? 1 : 0);
            assertEquals(1, changing, "axis-aligned: " + l);
            length += Math.abs(l.x2() - l.x1()) + Math.abs(l.y2() - l.y1()) + Math.abs(l.z2() - l.z1());
            for (double[] p : new double[][]{{l.x1(), l.y1(), l.z1()}, {l.x2(), l.y2(), l.z2()}}) {
                assertTrue((p[0] == -3 || p[0] == 1) && (p[1] == 10 || p[1] == 12) && (p[2] == 5 || p[2] == 6),
                        "ends on the box's corners: " + l);
            }
        }
        assertEquals(4 * (4 + 2 + 1), length);
    }

    @Test
    void othersZonesAreMarked() {
        List<ZoneOutlineRenderer.Line> lines = new ArrayList<>();
        ZoneOutlineRenderer.addLines(new ZoneOutlines.Box(0, 0, 0, 1, 1, 1, false), lines);
        assertTrue(lines.stream().noneMatch(ZoneOutlineRenderer.Line::own));
    }
}
