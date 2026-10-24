package eu.nurkert.photomaps.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class FrameListenerTest {

    @Test
    void rightIsClockwiseFromTheViewer() {
        // a frame facing south is looked at by someone facing north, whose right is east
        assertEquals(BlockFace.EAST, FrameListener.rightOf(BlockFace.SOUTH));
        assertEquals(BlockFace.WEST, FrameListener.rightOf(BlockFace.NORTH));
        assertEquals(BlockFace.NORTH, FrameListener.rightOf(BlockFace.EAST));
        assertEquals(BlockFace.SOUTH, FrameListener.rightOf(BlockFace.WEST));
    }
}
