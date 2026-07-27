package com.example.addon.utility;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Typeface;

import java.io.File;
import java.nio.file.Files;

public final class SkiaFontHelper {

    private static final String[] CROSS_PLATFORM_FALLBACKS = {
        "Arial", "Helvetica", "DejaVu Sans", "Liberation Sans", "Noto Sans", "Ubuntu", "FreeSans", "San Francisco", "sans-serif"
    };

    private SkiaFontHelper() {}

    public static Typeface createTypefaceFromFile(File fontFile) {
        if (fontFile != null && fontFile.exists()) {
            try {
                byte[] fontBytes = Files.readAllBytes(fontFile.toPath());
                FontMgr fm = getFontMgr();
                if (fm != null) {
                    Typeface tf = fm.makeFromData(Data.makeFromBytes(fontBytes));
                    if (tf != null) return tf;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Typeface matchTypeface(String family, FontStyle style) {
        if (family != null) {
            try {
                FontMgr fm = getFontMgr();
                if (fm != null) {
                    Typeface tf = fm.matchFamilyStyle(family, style);
                    if (tf != null) return tf;
                }
            } catch (Throwable ignored) {}
        }

        try {
            FontMgr fm = getFontMgr();
            if (fm != null) {
                for (String fallback : CROSS_PLATFORM_FALLBACKS) {
                    try {
                        Typeface tf = fm.matchFamilyStyle(fallback, style);
                        if (tf != null) return tf;
                    } catch (Throwable ignored) {}
                }
                try {
                    Typeface tf = fm.matchFamilyStyle(null, style);
                    if (tf != null) return tf;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            FontMgr fm = getFontMgr();
            if (fm != null) {
                return fm.matchFamilyStyle(null, FontStyle.NORMAL);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Font createFont(Typeface typeface, float size) {
        if (typeface == null) {
            typeface = matchTypeface(null, FontStyle.NORMAL);
        }
        if (typeface == null) return null;
        try {
            return new Font(typeface, size);
        } catch (Throwable t) {
            return null;
        }
    }

    public static FontMgr getFontMgr() {
        try {
            return FontMgr.getDefault();
        } catch (Throwable t) {
            return null;
        }
    }

    public static float measureTextWidth(Font font, String text) {
        if (font == null || text == null || text.isEmpty()) return 0f;
        try {
            return font.measureTextWidth(text);
        } catch (Throwable t) {
            return 0f;
        }
    }

    public static void safeClose(Font font) {
        if (font != null) {
            try {
                font.close();
            } catch (Throwable ignored) {}
        }
    }
}
