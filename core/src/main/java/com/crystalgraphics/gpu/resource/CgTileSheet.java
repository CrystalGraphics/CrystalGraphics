package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import java.nio.ByteBuffer;

/** One image cut into equal tiles: what a volume or an array loaded from a tile sheet takes, a slice or layer a tile. */
final class CgTileSheet {

    private CgTileSheet() {
    }

    /** Whether {@code sheet} divides into {@code columns} x {@code rows} equal tiles. */
    static boolean divides(CgImageData sheet, int columns, int rows) {
        return sheet.width() % columns == 0 && sheet.height() % rows == 0;
    }

    /**
     * {@code sheet}'s tiles in reading order, tile {@code (c, r)} from its top left at {@code r * columns + c}, each
     * tightly packed in the sheet's channels and row order. {@link #divides} first.
     */
    static ByteBuffer[] cut(CgImageData sheet, int columns, int rows) {
        int channels = sheet.channels(), w = sheet.width() / columns, h = sheet.height() / rows;
        int rowBytes = w * channels, sheetRow = sheet.width() * channels;
        ByteBuffer from = sheet.pixels();
        ByteBuffer[] tiles = new ByteBuffer[columns * rows];
        for (int t = 0; t < tiles.length; t++) {
            int x0 = (t % columns) * w, y0 = (t / columns) * h;
            ByteBuffer tile = ByteBuffer.allocateDirect(rowBytes * h);
            for (int y = 0; y < h; y++) {
                ByteBuffer row = from.duplicate();
                int at = (y0 + y) * sheetRow + x0 * channels;
                row.limit(at + rowBytes);
                row.position(at);
                tile.put(row);
            }
            tile.flip();
            tiles[t] = tile;
        }
        return tiles;
    }
}
