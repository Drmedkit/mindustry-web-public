#!/usr/bin/env python3
"""Generate Arc's TeaVM FreeType binding from the pinned gdx-teavm source."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "vendor/src/gdx-teavm-c864fc2523e2fe414ffc8ffefba7413ca103cd31/extensions/web/gdx-freetype-web/src/main/java/emu/com/badlogic/gdx/graphics/g2d/freetype/FreeType.java"
TARGET = ROOT / "Arc/backends/backend-teavm/src/arc/freetype/FreeType.java"


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    imports_end = text.index("public class FreeType")
    text = """package arc.freetype;

import arc.backend.teavm.TeaTypedArrays;
import arc.files.*;
import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import java.io.*;
import java.nio.*;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Int8Array;

""" + text[imports_end:]

    replacements = {
        "FileHandle": "Fi",
        "GdxRuntimeException": "ArcRuntimeException",
        "BufferUtils.newByteBuffer": "Buffers.newUnsafeByteBuffer",
        "BufferUtils.copy": "Buffers.copy",
        "StreamUtils.copyStreamToByteArray": "Streams.copyBytes",
        "StreamUtils.copyStream": "Streams.copy",
        "StreamUtils.closeQuietly": "Streams.close",
        "TypedArrays": "TeaTypedArrays",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    text = text.replace("TeaTeaTypedArrays", "TeaTypedArrays")

    method_start = text.index("        public Pixmap getPixmap")
    method_end = text.index("        // @off", method_start)
    pixmap_method = """        public Pixmap getPixmap(Color color, float gamma) {
            int width = getWidth(), rows = getRows();
            ByteBuffer src = getBuffer();
            Pixmap pixmap = new Pixmap(width, rows);
            int pixelMode = getPixelMode();
            int rowBytes = Math.abs(getPitch());
            int rgba = color.rgba();
            int rgb = rgba & 0xffffff00;
            int baseAlpha = rgba & 0xff;
            byte[] srcRow = new byte[rowBytes];

            for(int y = 0; y < rows; y++) {
                src.get(srcRow);
                if(pixelMode == FT_PIXEL_MODE_MONO) {
                    for(int i = 0, x = 0; x < width; i++, x += 8) {
                        byte bits = srcRow[i];
                        for(int bit = 0, count = Math.min(8, width - x); bit < count; bit++) {
                            pixmap.setRaw(x + bit, y, (bits & (1 << (7 - bit))) != 0 ? rgba : 0);
                        }
                    }
                } else {
                    for(int x = 0; x < width; x++) {
                        int alpha = srcRow[x] & 0xff;
                        int outAlpha = alpha == 0 ? 0 : alpha == 255 ? baseAlpha
                            : (int)(baseAlpha * (float)Math.pow(alpha / 255f, gamma));
                        pixmap.setRaw(x, y, rgb | outAlpha);
                    }
                }
            }
            return pixmap;
        }
"""
    text = text[:method_start] + pixmap_method + text[method_end:]

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(text, encoding="utf-8")
    print(f"Generated {TARGET.relative_to(ROOT)} from pinned gdx-teavm source.")


if __name__ == "__main__":
    main()
