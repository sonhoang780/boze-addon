package com.example.addon.modules;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.lwjgl.glfw.GLFW;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;

public class EbookReader extends AddonModule {
    public static final EbookReader INSTANCE = new EbookReader();
    public boolean active = false;

    public final ToggleOption showTitle = new ToggleOption(this, "Show Title", "", true);

    private final List<File> availableBooks = new ArrayList<>();
    private long lastScanTime = -1L;

    private File currentBookFile = null;
    private String currentBookTitle = "";
    private float readerFontSize = 14f;

    private float pageFlipProgress = 1.0f;
    private int pageFlipDir = 1;
    private long lastFrameTime = 0L;

    private DirectContext skiaContext;
    
    // BỘ FONT ĐA DẠNG ĐỂ HIỂN THỊ RICH TEXT
    private Font fontReg, fontBold, fontItalic, fontBoldItalic;
    private float lastLoadedFontSize = -1f;

    // ─── MINI WEB ENGINE: CẤU TRÚC LƯU TRỮ RICH TEXT & HÌNH ẢNH ───
    private static abstract class RichToken {}
    private static class RichWord extends RichToken {
        String text; boolean bold, italic, heading;
        RichWord(String t, boolean b, boolean i, boolean h) { text=t; bold=b; italic=i; heading=h; }
    }
    private static class RichImage extends RichToken {
        Image img; float aspect;
        RichImage(Image i) { img=i; aspect = (float)i.getWidth() / i.getHeight(); }
    }
    private static class RichBreak extends RichToken {}

    private static abstract class RenderCmd {}
    private static class TextCmd extends RenderCmd {
        String text; float x, y; boolean bold, italic, heading;
        TextCmd(String t, float x, float y, boolean b, boolean i, boolean h) { this.text=t; this.x=x; this.y=y; this.bold=b; this.italic=i; this.heading=h; }
    }
    private static class ImageCmd extends RenderCmd {
        Image img; float x, y, w, h;
        ImageCmd(Image i, float x, float y, float w, float h) { this.img=i; this.x=x; this.y=y; this.w=w; this.h=h; }
    }
    private static class Page { List<RenderCmd> cmds = new ArrayList<>(); }

    private List<RichToken> globalTokens = new ArrayList<>();
    private List<Page> currentPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private float currentLayoutW = -1, currentLayoutH = -1;

    private EbookReader() {
        super("EbookReader", "Read ebooks, open boze/ebook to import epub/txt files.");
    }

    @Override
    public void onEnable() {
        this.active = true;
        scanBooksIfNeeded(true);
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new LibraryScreen()));
    }

    @Override
    public void onDisable() {
        this.active = false;
        closeCurrentBook();
    }

    private void closeCurrentBook() {
        currentBookFile = null;
        for (RichToken tk : globalTokens) {
            if (tk instanceof RichImage) ((RichImage)tk).img.close();
        }
        globalTokens.clear();
        currentPages.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // LÕI PARSER EPUB VÀ THÔNG DỊCH DOM HTML
    // ─────────────────────────────────────────────────────────────
    private void loadBook(File file) {
        closeCurrentBook();
        currentBookFile = file;
        currentPageIndex = 0;
        pageFlipProgress = 1.0f;
        currentLayoutW = -1; 
        
        String name = file.getName().toLowerCase();
        String title = file.getName();
        if (title.lastIndexOf('.') > 0) title = title.substring(0, title.lastIndexOf('.'));

        try {
            if (name.endsWith(".epub")) {
                title = parseEpubRich(file, title);
            } else {
                String raw = Files.readString(file.toPath());
                globalTokens = parseHtmlToTokens(raw, null, "");
            }
        } catch (Exception e) {
            globalTokens.add(new RichWord("Lỗi đọc file: " + e.getMessage(), false, false, false));
        }
        currentBookTitle = title;
    }

    private String parseEpubRich(File epubFile, String defTitle) throws IOException {
        String title = defTitle;
        try (ZipFile zip = new ZipFile(epubFile)) {
            ZipEntry opfEntry = findOpfEntry(zip);
            List<String> htmlPaths = new ArrayList<>();

            if (opfEntry != null) {
                String opfContent = new String(zip.getInputStream(opfEntry).readAllBytes(), "UTF-8");
                Matcher titleM = Pattern.compile("<dc:title[^>]*>([^<]*)</dc:title>").matcher(opfContent);
                if (titleM.find()) title = titleM.group(1).trim();

                java.util.Map<String, String> manifest = new java.util.HashMap<>();
                Matcher m1 = Pattern.compile("<item[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"").matcher(opfContent);
                while (m1.find()) manifest.put(m1.group(1), m1.group(2));
                Matcher m2 = Pattern.compile("<item[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"").matcher(opfContent);
                while (m2.find()) manifest.putIfAbsent(m2.group(2), m2.group(1));

                String opfDir = opfEntry.getName().contains("/") ? opfEntry.getName().substring(0, opfEntry.getName().lastIndexOf('/') + 1) : "";
                Matcher spineM = Pattern.compile("<itemref[^>]*idref=\"([^\"]+)\"").matcher(opfContent);
                while (spineM.find()) {
                    String href = manifest.get(spineM.group(1));
                    if (href != null) htmlPaths.add(normalizeZipPath(opfDir + href));
                }
            } else {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName().toLowerCase();
                    if (n.endsWith(".xhtml") || n.endsWith(".html")) htmlPaths.add(e.getName());
                }
                htmlPaths.sort(String::compareToIgnoreCase);
            }

            for (String path : htmlPaths) {
                ZipEntry e = zip.getEntry(path);
                if (e == null) continue;
                String html = new String(zip.getInputStream(e).readAllBytes(), "UTF-8");
                String htmlDir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
                globalTokens.addAll(parseHtmlToTokens(html, zip, htmlDir));
                globalTokens.add(new RichBreak());
            }
        }
        return title;
    }

    private List<RichToken> parseHtmlToTokens(String html, ZipFile zip, String htmlDir) {
        List<RichToken> tokens = new ArrayList<>();
        
        // CHỈ CẮT BỎ STYLE VÀ SCRIPT. KHÔNG ĐƯỢC XÓA <SVG> VÌ ẢNH BÌA HAY NẰM TRONG ĐÓ!
        html = html.replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", "");
        
        boolean bold = false, italic = false, heading = false;
        Matcher m = Pattern.compile("(<[^>]+>|[^<]+)").matcher(html);
        
        while (m.find()) {
            String part = m.group(1);
            if (part.startsWith("<")) {
                String tag = part.toLowerCase().replaceAll("\\s+", " ");
                // Nhận diện Tag siêu chặt chẽ
                if (tag.matches("<(b|strong)\\b.*>")) bold = true;
                else if (tag.matches("</(b|strong)>")) bold = false;
                else if (tag.matches("<(i|em)\\b.*>")) italic = true;
                else if (tag.matches("</(i|em)>")) italic = false;
                else if (tag.matches("<h[1-6]\\b.*>")) { heading = true; bold = true; tokens.add(new RichBreak()); }
                else if (tag.matches("</h[1-6]>")) { heading = false; bold = false; tokens.add(new RichBreak()); }
                else if (tag.startsWith("<br") || tag.startsWith("</p") || tag.startsWith("</div")) tokens.add(new RichBreak());
                else if ((tag.startsWith("<img") || tag.startsWith("<image")) && zip != null) {
                    Matcher srcM = Pattern.compile("(?:src|href|xlink:href)=\"([^\"]+)\"").matcher(part);
                    if (srcM.find()) {
                        String srcRaw = srcM.group(1);
                        try { srcRaw = java.net.URLDecoder.decode(srcRaw, "UTF-8"); } catch (Exception e) {}
                        String srcPath = normalizeZipPath(htmlDir + srcRaw);
                        Image img = loadZipImage(zip, srcPath);
                        if (img != null) {
                            tokens.add(new RichBreak());
                            tokens.add(new RichImage(img));
                            tokens.add(new RichBreak());
                        }
                    }
                }
            } else {
                String text = decodeEntities(part).replaceAll("[ \\t\\r\\n]+", " ");
                if (text.isBlank()) continue;
                for (String word : text.split(" ")) {
                    if (!word.isEmpty()) tokens.add(new RichWord(word, bold, italic, heading));
                }
            }
        }
        return tokens;
    }

    private Image loadZipImage(ZipFile zip, String path) {
        try {
            ZipEntry e = zip.getEntry(path);
            // TRUY LÙNG TẬN CÙNG: Nếu lệch chữ Hoa/Thường thì tự động quét lại toàn bộ file ZIP để tìm
            if (e == null) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    if (ze.getName().equalsIgnoreCase(path)) { e = ze; break; }
                }
            }
            if (e == null) return null;
            try (InputStream is = zip.getInputStream(e)) {
                return Image.makeFromEncoded(is.readAllBytes());
            }
        } catch (Exception ex) { return null; }
    }

    private String decodeEntities(String s) {
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
             .replace("&mdash;", "—").replace("&ndash;", "–").replace("&ldquo;", "“")
             .replace("&rdquo;", "”").replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&hellip;", "…");
        // BỘ DỊCH MÃ HEXA TỐI THƯỢNG (Khắc phục lỗi &#x201c;)
        s = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(s).replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1), 16)));
        s = Pattern.compile("&#([0-9]+);").matcher(s).replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1))));
        return s;
    }

    private ZipEntry findOpfEntry(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container != null) {
            String content = new String(zip.getInputStream(container).readAllBytes(), "UTF-8");
            Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(content);
            if (m.find() && zip.getEntry(m.group(1)) != null) return zip.getEntry(m.group(1));
        }
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().toLowerCase().endsWith(".opf")) return e;
        }
        return null;
    }

    private String normalizeZipPath(String path) {
        path = path.replace("./", "");
        while (path.contains("/../")) path = path.replaceFirst("[^/]+/\\.\\./", "");
        return path;
    }

    private File getEbookDir() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/ebook");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void scanBooksIfNeeded(boolean force) {
        File dir = getEbookDir();
        long mod = dir.lastModified();
        if (!force && mod == lastScanTime) return;
        lastScanTime = mod;
        availableBooks.clear();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".txt") || name.endsWith(".epub")) availableBooks.add(f);
        }
        availableBooks.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    private void buildFonts(float size) {
        if (fontReg != null && lastLoadedFontSize == size) return;
        if (fontReg != null) { fontReg.close(); fontBold.close(); fontItalic.close(); fontBoldItalic.close(); }
        
        FontMgr fm = FontMgr.getDefault();
        Typeface baseTf = FontMgr.getDefault().matchFamilyStyle(null, FontStyle.NORMAL);
        String familyName = baseTf.getFamilyName(); // Lấy tên họ Font mặc định (VD: Segoe UI, Arial)

        // Gọi thẳng bản ngã Đậm/Nghiêng HÀNG AUTH 100% từ Hệ điều hành
        Typeface tfReg = fm.matchFamilyStyle(familyName, FontStyle.NORMAL);
        Typeface tfBold = fm.matchFamilyStyle(familyName, FontStyle.BOLD);
        Typeface tfItalic = fm.matchFamilyStyle(familyName, FontStyle.ITALIC);
        Typeface tfBoldItal = fm.matchFamilyStyle(familyName, FontStyle.BOLD_ITALIC);

        // Fallback an toàn để chống crash nếu HĐH bị khuyết tật file font
        if (tfReg == null) tfReg = baseTf;
        if (tfBold == null) tfBold = baseTf;
        if (tfItalic == null) tfItalic = baseTf;
        if (tfBoldItal == null) tfBoldItal = baseTf;

        fontReg = new Font(tfReg, size);
        fontBold = new Font(tfBold, size); // Hàng Real đéo cần setEmbolden!
        fontItalic = new Font(tfItalic, size);
        fontBoldItalic = new Font(tfBoldItal, size);

        // Đề phòng máy tính của mày mất file Italic gốc, tao mới cho phép ép nghiêng vật lý (chỉ nghiêng, không bóp nét)
        if (tfItalic == baseTf) fontItalic.setSkewX(-0.25f);
        if (tfBoldItal == baseTf) fontBoldItalic.setSkewX(-0.25f);

        lastLoadedFontSize = size;
    }

    private Font getFontFor(boolean bold, boolean italic, boolean heading) {
        if (heading) return fontBold; // Tiêu đề mặc định in đậm to
        if (bold && italic) return fontBoldItalic;
        if (bold) return fontBold;
        if (italic) return fontItalic;
        return fontReg;
    }

    // ─────────────────────────────────────────────────────────────
    // THUẬT TOÁN DÀN TRANG ĐỘNG (PAGINATION) CHUẨN SKIA
    // ─────────────────────────────────────────────────────────────
    private void layoutPagesIfNeeded(float maxW, float maxH) {
        if (Math.abs(currentLayoutW - maxW) < 1f && Math.abs(currentLayoutH - maxH) < 1f && currentPages.size() > 0) return;
        currentPages.clear();
        currentLayoutW = maxW; currentLayoutH = maxH;
        
        Page curPage = new Page();
        float curX = 0, curY = 0;
        float normalLineH = fontReg.getSize() * 1.5f;
        float headingLineH = fontReg.getSize() * 2.0f; // Tiêu đề cao hơn

        for (RichToken tk : globalTokens) {
            if (tk instanceof RichBreak) {
                if (curX > 0 || curY > 0) { curX = 0; curY += normalLineH; }
                continue;
            }
            if (tk instanceof RichImage) {
                RichImage ri = (RichImage) tk;
                float drawW = Math.min(ri.img.getWidth(), maxW);
                float drawH = drawW / ri.aspect;
                if (drawH > maxH * 0.7f) { drawH = maxH * 0.7f; drawW = drawH * ri.aspect; } 
                
                if (curX > 0) { curX = 0; curY += normalLineH; }
                if (curY + drawH > maxH && !curPage.cmds.isEmpty()) {
                    currentPages.add(curPage); curPage = new Page(); curY = 0;
                }
                float cx = (maxW - drawW) / 2f; 
                curPage.cmds.add(new ImageCmd(ri.img, cx, curY, drawW, drawH));
                curY += drawH + normalLineH * 0.5f;
                curX = 0;
            }
            if (tk instanceof RichWord) {
                RichWord rw = (RichWord) tk;
                Font f = getFontFor(rw.bold, rw.italic, rw.heading);
                if (rw.heading) f = new Font(f.getTypeface(), fontReg.getSize() * 1.5f); // Scale font cho heading
                
                float w = f.measureTextWidth(rw.text + " ");
                float h = rw.heading ? headingLineH : normalLineH;
                
                if (curX + w > maxW && curX > 0) { curX = 0; curY += normalLineH; }
                if (curY + normalLineH > maxH && !curPage.cmds.isEmpty()) {
                    currentPages.add(curPage); curPage = new Page(); curY = 0; curX = 0;
                }
                curPage.cmds.add(new TextCmd(rw.text + " ", curX, curY, rw.bold, rw.italic, rw.heading));
                curX += w;
                
                if (rw.heading) f.close(); // Giải phóng font temp của heading
            }
        }
        if (!curPage.cmds.isEmpty() || currentPages.isEmpty()) currentPages.add(curPage);
        if (currentPageIndex >= currentPages.size()) currentPageIndex = Math.max(0, currentPages.size() - 1);
    }

    private void drawSkiaPanel(double x, double y, double w, double h, float radius, boolean enableGlow) {
        MinecraftClient mc = MinecraftClient.getInstance();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);
        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll();

        int mainFboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                0, 0, mainFboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
             Surface surface = Surface.wrapBackendRenderTarget(
                skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, io.github.humbleui.skija.ColorType.RGBA_8888, ColorSpace.getSRGB(), null)) {

            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);

            if (enableGlow) {
                try (Paint glowPaint = new Paint(); MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.OUTER, 14f)) {
                    glowPaint.setColor(new Color(255, 255, 255, 200).getRGB());
                    glowPaint.setMaskFilter(blur); glowPaint.setAntiAlias(true);
                    canvas.drawRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), glowPaint);
                    
                }
            }

            try (Paint strokePaint = new Paint()) {
                strokePaint.setColor(new Color(255, 255, 255, 70).getRGB());
                strokePaint.setMode(PaintMode.STROKE); strokePaint.setStrokeWidth(1.2f); strokePaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), strokePaint);
            }

            canvas.save();
            canvas.clipRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), ClipMode.INTERSECT, true);
            try (ImageFilter backdropBlur = ImageFilter.makeBlur(18f, 18f, FilterTileMode.CLAMP)) {
                canvas.saveLayer(new SaveLayerRec(null, null, backdropBlur));
                try (Paint bgPaint = new Paint()) {
                    bgPaint.setColor(new Color(18, 18, 22, 215).getRGB()); bgPaint.setAntiAlias(true);
                    canvas.drawRect(Rect.makeXYWH((float)x, (float)y, (float)w, (float)h), bgPaint);
                }
                canvas.restore();
            }
            canvas.restore();
            skiaContext.flushAndSubmit(false);
        }
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
    }

    public class LibraryScreen extends net.minecraft.client.gui.screen.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownLib = false;
        private File selectedBook = null;
        private long lastClickTime = 0L;
        private File lastClickedFile = null;

        public LibraryScreen() { super(net.minecraft.text.Text.literal("Ebook Library")); }

        @Override protected void init() { previousHudHidden = client.options.hudHidden; client.options.hudHidden = true; scanBooksIfNeeded(false); }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x66000000);
            int winX = (this.width - 220) / 2; int winY = (this.height - 300) / 2;
            drawSkiaPanel(winX, winY, 220, 300, 8f, true);

            context.drawText(client.textRenderer, "Ebook Library", winX + (220 - client.textRenderer.getWidth("Ebook Library")) / 2, winY + 10, 0xFFFFFFFF, true);
            context.fill(winX + 10, winY + 26, winX + 210, winY + 256, 0x44000000);

            int itemH = 16; int startY = winY + 28;
            for (int i = 0; i < availableBooks.size() && i < 14; i++) {
                File f = availableBooks.get(i); int itemY = startY + i * itemH;
                String name = f.getName().substring(0, f.getName().lastIndexOf('.') > 0 ? f.getName().lastIndexOf('.') : f.getName().length());
                boolean isSelected = (selectedBook != null && selectedBook.getAbsolutePath().equals(f.getAbsolutePath()));
                boolean isHovered  = mouseX >= winX + 10 && mouseX <= winX + 210 && mouseY >= itemY && mouseY < itemY + itemH;

                if (isSelected) context.fill(winX + 12, itemY, winX + 208, itemY + itemH - 1, new Color(255, 255, 255, 70).getRGB());
                else if (isHovered) context.fill(winX + 12, itemY, winX + 208, itemY + itemH - 1, 0x33FFFFFF);

                String ext = f.getName().toLowerCase().endsWith(".epub") ? "[EPUB] " : "[TXT] ";
                context.drawText(client.textRenderer, ext + name, winX + 16, itemY + 4, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, true);
            }

            boolean hoverRead = mouseX >= winX + 15 && mouseX <= winX + 95 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 15, winY + 266, winX + 95, winY + 286, selectedBook == null ? 0xFF1A1A1A : (hoverRead ? 0xFF444444 : 0xFF222222));
            context.drawText(client.textRenderer, "Read", winX + 15 + (80 - client.textRenderer.getWidth("Read")) / 2, winY + 266 + 6, selectedBook != null ? 0xFFFFFFFF : 0xFF666666, true);

            boolean hoverClose = mouseX >= winX + 125 && mouseX <= winX + 205 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 125, winY + 266, winX + 205, winY + 286, hoverClose ? 0xFF444444 : 0xFF222222);
            context.drawText(client.textRenderer, "Close", winX + 125 + (80 - client.textRenderer.getWidth("Close")) / 2, winY + 266 + 6, 0xFFFFFFFF, true);

            boolean mouseDown = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (mouseDown && !wasMouseDownLib) {
                if (hoverRead && selectedBook != null) { loadBook(selectedBook); client.setScreen(new ReaderScreen()); } 
                else if (hoverClose) this.close();
                else if (mouseX >= winX + 10 && mouseX <= winX + 210 && mouseY >= winY + 26 && mouseY <= winY + 256) {
                    int clickedIdx = (mouseY - startY) / itemH;
                    if (clickedIdx >= 0 && clickedIdx < availableBooks.size() && clickedIdx < 14) {
                        File clicked = availableBooks.get(clickedIdx);
                        long now = System.currentTimeMillis();
                        if (clicked.equals(lastClickedFile) && (now - lastClickTime) < 400) {
                            loadBook(clicked); client.setScreen(new ReaderScreen());
                        } else {
                            selectedBook = clicked; lastClickedFile = clicked; lastClickTime = now;
                        }
                    }
                }
            }
            wasMouseDownLib = mouseDown;
            super.render(context, mouseX, mouseY, delta);
        }

        @Override public void close() { client.options.hudHidden = previousHudHidden; EbookReader.this.setState(false); super.close(); }
        @Override public boolean shouldPause() { return false; }
    }

    public class ReaderScreen extends net.minecraft.client.gui.screen.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownReader = false;
        private float zoomScale = 1.0f;
        
        // --- TEXTFIELD ĐỂ NHẬP TRANG ---
        private net.minecraft.client.gui.widget.TextFieldWidget pageInputWidget;
        private boolean isEditingPage = false;

        public ReaderScreen() { super(net.minecraft.text.Text.literal("Ebook Reader")); }

        @Override protected void init() { 
            previousHudHidden = client.options.hudHidden; 
            client.options.hudHidden = true; 
            
            // Khởi tạo ô nhập số trang xịn xò của Minecraft
            pageInputWidget = new net.minecraft.client.gui.widget.TextFieldWidget(client.textRenderer, 0, 0, 40, 12, net.minecraft.text.Text.literal(""));
            pageInputWidget.setMaxLength(5); // Sách 99999 trang là max
            pageInputWidget.setVisible(false);
            pageInputWidget.setDrawsBackground(true);
            // BIỂU THỨC CHÍNH QUY (REGEX): Cấm tuyệt đối số âm, số thực, chữ cái. Chỉ cho gõ số (0-9)
            pageInputWidget.setTextPredicate(text -> text.isEmpty() || text.matches("^[0-9]+$"));
            this.addDrawableChild(pageInputWidget);
        }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            long now = System.currentTimeMillis(); float deltaMs = lastFrameTime == 0 ? 16f : (now - lastFrameTime); lastFrameTime = now;
            if (pageFlipProgress < 1.0f) { pageFlipProgress += deltaMs / 280.0f; if (pageFlipProgress > 1.0f) pageFlipProgress = 1.0f; }

            context.fill(0, 0, this.width, this.height, 0x77000000);

            float baseW = 400, baseH = 480;
            float panelW = baseW * zoomScale, panelH = baseH * zoomScale;
            float winX = (this.width - panelW) / 2f, winY = (this.height - panelH) / 2f;

            float slideOffset = 0f;
            if (pageFlipProgress < 1.0f) slideOffset = (1f - (1f - (float)Math.pow(1f - pageFlipProgress, 3))) * 40f * pageFlipDir;

            drawSkiaPanel(winX + slideOffset, winY, panelW, panelH, 10f, true);

            int contentTop = (int) winY + 14;
            if (showTitle.getValue()) {
                context.drawText(client.textRenderer, currentBookTitle, (int)(winX + slideOffset + (panelW - client.textRenderer.getWidth(currentBookTitle)) / 2f), contentTop, 0xFFFFFFFF, true);
                contentTop += 16;
            }

            buildFonts(readerFontSize * zoomScale);
            layoutPagesIfNeeded(panelW - 36, panelH - (contentTop - winY) - 40);

            int textAlpha = (int)(255 * Math.min(1f, pageFlipProgress * 1.6f));
            if (!currentPages.isEmpty()) {
                drawRichPage(currentPages.get(currentPageIndex), winX + slideOffset + 18, contentTop, textAlpha);
            }

            // ── LOGIC HIỂN THỊ VÀ CLICK CHỌN TRANG ──
            String pageInfo = (currentPageIndex + 1) + " / " + Math.max(1, currentPages.size());
            int pageInfoW = client.textRenderer.getWidth(pageInfo);
            int pageInfoX = (int)(winX + slideOffset + (panelW - pageInfoW) / 2f);
            int pageInfoY = (int)(winY + panelH - 16);
            
            // Hover đổi màu báo hiệu cho user biết có thể click được
            boolean hoverPage = mouseX >= pageInfoX && mouseX <= pageInfoX + pageInfoW && mouseY >= pageInfoY && mouseY <= pageInfoY + 9;

            if (isEditingPage) {
                // Di chuyển Text Field đè lên đúng chỗ chữ Page đang đứng
                pageInputWidget.setX(pageInfoX + pageInfoW / 2 - 20);
                pageInputWidget.setY(pageInfoY - 2);
            } else {
                context.drawText(client.textRenderer, pageInfo, pageInfoX, pageInfoY, hoverPage ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            }

            int btnY = (int)(winY + panelH - 30);
            int btnPrevX = (int)(winX + slideOffset + panelW / 2f - 24);
            int btnNextX = (int)(winX + slideOffset + panelW / 2f + 12);
            boolean hoverPrev = mouseX >= btnPrevX && mouseX <= btnPrevX + 12 && mouseY >= btnY && mouseY <= btnY + 12;
            boolean hoverNext = mouseX >= btnNextX && mouseX <= btnNextX + 12 && mouseY >= btnY && mouseY <= btnY + 12;
            
            context.fill(btnPrevX, btnY, btnPrevX + 12, btnY + 12, hoverPrev ? 0x55FFFFFF : 0x22FFFFFF);
            context.drawText(client.textRenderer, "<", btnPrevX + 3, btnY + 2, 0xFFFFFFFF, true);
            context.fill(btnNextX, btnY, btnNextX + 12, btnY + 12, hoverNext ? 0x55FFFFFF : 0x22FFFFFF);
            context.drawText(client.textRenderer, ">", btnNextX + 3, btnY + 2, 0xFFFFFFFF, true);

            int zoomBtnY = (int) winY + 8;
            int zOutX = (int)(winX + slideOffset + panelW - 48), zInX = (int)(winX + slideOffset + panelW - 24);
            boolean hZOut = mouseX >= zOutX && mouseX <= zOutX + 16 && mouseY >= zoomBtnY && mouseY <= zoomBtnY + 16;
            boolean hZIn  = mouseX >= zInX  && mouseX <= zInX + 16  && mouseY >= zoomBtnY && mouseY <= zoomBtnY + 16;
            context.fill(zOutX, zoomBtnY, zOutX + 16, zoomBtnY + 16, hZOut ? 0x66FFFFFF : 0x33FFFFFF);
            context.fill(zInX, zoomBtnY, zInX + 16, zoomBtnY + 16, hZIn ? 0x66FFFFFF : 0x33FFFFFF);
            context.drawText(client.textRenderer, "-", zOutX + 6, zoomBtnY + 4, 0xFFFFFFFF, true);
            context.drawText(client.textRenderer, "+", zInX + 5, zoomBtnY + 4, 0xFFFFFFFF, true);

            // ── BẮT TẤT CẢ CÁC LOẠI CLICK CHUỘT (TRÁI/PHẢI/GIỮA) ──
            boolean leftClick = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightClick = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            boolean midClick = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            boolean anyClick = leftClick || rightClick || midClick;

            if (anyClick && !wasMouseDownReader) {
                if (isEditingPage) {
                    // Nếu click ra ngoài ô nhập, tự động Submit chốt trang
                    if (!pageInputWidget.isMouseOver(mouseX, mouseY)) {
                        submitPageInput();
                    }
                } else {
                    if (hoverPage) {
                        // Kích hoạt ô gõ chữ
                        isEditingPage = true;
                        pageInputWidget.setText(String.valueOf(currentPageIndex + 1));
                        pageInputWidget.setVisible(true);
                        this.setFocused(pageInputWidget);
                        pageInputWidget.setFocused(true);
                    } else if (leftClick) {
                        if (hoverPrev && currentPageIndex > 0) { currentPageIndex--; pageFlipDir = -1; pageFlipProgress = 0f; }
                        else if (hoverNext && currentPageIndex < currentPages.size() - 1) { currentPageIndex++; pageFlipDir = 1; pageFlipProgress = 0f; }
                        else if (hZOut) { zoomScale = Math.max(0.6f, zoomScale - 0.1f); currentLayoutW = -1; }
                        else if (hZIn) { zoomScale = Math.min(2.0f, zoomScale + 0.1f); currentLayoutW = -1; }
                    }
                }
            }
            wasMouseDownReader = anyClick;

            // Chốt hạ: Vẽ các component tĩnh gốc của Screen (bao gồm cả pageInputWidget)
            super.render(context, mouseX, mouseY, delta);
        }

        private void submitPageInput() {
            try {
                int targetPage = Integer.parseInt(pageInputWidget.getText().trim());
                int totalPages = Math.max(1, currentPages.size());
                // ÉP KHUNG GIỚI HẠN: Nếu gõ lố > max hoặc gõ số 0, tự động ép về giới hạn
                targetPage = Math.max(1, Math.min(targetPage, totalPages));
                
                int newIndex = targetPage - 1;
                if (newIndex != currentPageIndex) {
                    pageFlipDir = (newIndex > currentPageIndex) ? 1 : -1;
                    currentPageIndex = newIndex;
                    pageFlipProgress = 0f; // Kích hoạt animation trượt trang
                }
            } catch (Exception ignored) {} 
            
            isEditingPage = false;
            pageInputWidget.setVisible(false);
        }

        private void drawRichPage(Page page, float startX, float startY, int alpha) {
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
            org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
            org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);

            if (skiaContext == null) skiaContext = DirectContext.makeGL();
            skiaContext.resetAll();
            
            try (BackendRenderTarget rt = BackendRenderTarget.makeGL(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(), 0, 0, org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING), org.lwjgl.opengl.GL30C.GL_RGBA8);
                 Surface surface = Surface.wrapBackendRenderTarget(skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, io.github.humbleui.skija.ColorType.RGBA_8888, ColorSpace.getSRGB(), null)) {
                Canvas canvas = surface.getCanvas();
                float scale = (float) client.getWindow().getScaleFactor();
                canvas.scale(scale, scale);

                try (Paint paint = new Paint()) {
                    paint.setAntiAlias(true);
                    paint.setColor(new Color(235, 235, 235, Math.max(0, Math.min(255, alpha))).getRGB());

                    for (RenderCmd cmd : page.cmds) {
                        if (cmd instanceof TextCmd) {
                            TextCmd t = (TextCmd) cmd;
                            Font f = getFontFor(t.bold, t.italic, t.heading);

                            if (t.heading) {
                                try (Font hf = new Font(f.getTypeface(), fontReg.getSize() * 1.5f)) {
                                    canvas.drawString(t.text, startX + t.x, startY + t.y + hf.getSize(), hf, paint);
                                }
                            } else {
                                canvas.drawString(t.text, startX + t.x, startY + t.y + f.getSize(), f, paint);
                            }
                        } else if (cmd instanceof ImageCmd) {
                            ImageCmd imgCmd = (ImageCmd) cmd;
                            if (imgCmd.img != null && !imgCmd.img.isClosed()) {
                                try (Paint imgPaint = new Paint()) {
                                    imgPaint.setAlphaf(alpha / 255f);
                                    canvas.drawImageRect(imgCmd.img, Rect.makeXYWH(0, 0, imgCmd.img.getWidth(), imgCmd.img.getHeight()), Rect.makeXYWH(startX + imgCmd.x, startY + imgCmd.y, imgCmd.w, imgCmd.h), imgPaint);
                                }
                            }
                        }
                    }
                }
                skiaContext.flushAndSubmit(false);
            }

            org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
            org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
            org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
            org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
        }

        // ĐÃ SỬA LẠI ĐÚNG CHUẨN KEYPRESSED CỦA MINECRAFT 1.21 ĐỂ BÀN PHÍM HOẠT ĐỘNG HOÀN HẢO
        // ĐÃ TRẢ VỀ ĐÚNG CHUẨN KEYINPUT CỦA BOZE API
        @Override 
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            int keyCode = input.key(); // Lấy mã phím từ Object KeyInput
            
            if (isEditingPage) {
                // Nhấn Enter để chốt sổ số trang
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                    submitPageInput();
                    return true;
                }
                // Nhấn Esc để hủy bỏ việc gõ số, trở về như cũ không đóng sách
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                    isEditingPage = false;
                    pageInputWidget.setVisible(false);
                    return true; 
                }
                return super.keyPressed(input);
            }

            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { this.close(); return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT && currentPageIndex > 0) { currentPageIndex--; pageFlipDir = -1; pageFlipProgress = 0f; return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT && currentPageIndex < currentPages.size() - 1) { currentPageIndex++; pageFlipDir = 1; pageFlipProgress = 0f; return true; }
            
            return super.keyPressed(input);
        }

        @Override public void close() { client.options.hudHidden = previousHudHidden; client.setScreen(new LibraryScreen()); }
        @Override public boolean shouldPause() { return false; }
    }
}