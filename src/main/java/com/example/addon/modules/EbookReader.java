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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.example.addon.mixin.GuiGraphicsExtractorAccessor;
import com.example.addon.screens.CachedSkiaTexture;
import com.example.addon.screens.EbookPagePipState;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;

public class EbookReader extends AddonModule {
    public static final EbookReader INSTANCE = new EbookReader();
    public boolean active = false;

    public final ToggleOption showTitle = new ToggleOption(this, "ShowTitle", "", true);

    private final List<File> availableBooks = new ArrayList<>();
    private long lastScanTime = -1L;

    private File currentBookFile = null;
    private String currentBookTitle = "";
    private float readerFontSize = 14f;

    // pageFlipProgress: 0 -> 1 over FLIP_DURATION_MS. While < 1, `flippingSide` page is
    // physically curling (mesh warp, see renderCurlMesh) and `currentPageIndex` still
    // points at the OLD sheet -- flipped to `pendingSheetIndex` the instant progress
    // reaches 1 (paintCurlingPage below draws the destination sheet underneath the
    // whole time, so the curl always reveals real incoming content, not a blank page).
    private float pageFlipProgress = 1.0f;
    private int pageFlipDir = 1;
    private long lastFrameTime = 0L;
    private static final long FLIP_DURATION_MS = 550L;
    private static final float CURL_RADIUS_FRAC = 0.22f; // fraction of half-page width

    private enum FlipSide { LEFT, RIGHT }
    private FlipSide flippingSide = null;
    private int flippingPageIndex = -1;   // index into currentPages of the page physically curling
    private int pendingSheetIndex = 0;    // currentPageIndex value to commit to when progress hits 1
    private Image flipSourceImage = null; // snapshot of flippingPageIndex's content, taken once when the flip starts
    private float flipSourceW = 0, flipSourceH = 0;

    private CachedSkiaTexture panelTex;

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
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new LibraryScreen()));
    }

    @Override
    public void onDisable() {
        this.active = false;
        closeCurrentBook();
        if (panelTex != null) { panelTex.dispose(); panelTex = null; }
    }

    private void closeCurrentBook() {
        currentBookFile = null;
        for (RichToken tk : globalTokens) {
            if (tk instanceof RichImage) ((RichImage)tk).img.close();
        }
        globalTokens.clear();
        currentPages.clear();
        clearFlipState();
    }

    private void clearFlipState() {
        if (flipSourceImage != null) { flipSourceImage.close(); flipSourceImage = null; }
        flippingSide = null;
        flippingPageIndex = -1;
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

    private String lastImageLoadError = "";

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
            if (e == null) { lastImageLoadError = "zip entry not found: " + path; return null; }
            try (InputStream is = zip.getInputStream(e)) {
                return Image.makeFromEncoded(is.readAllBytes());
            }
        } catch (Exception ex) {
            lastImageLoadError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return null;
        }
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

    /**
     * "../Images/Cover.jpg" resolved against "OEBPS/Text/" used to come out as
     * "OEBPS/Text/.Images/Cover.jpg" -- every epub image (cover, illustrations,
     * chapter headers) silently failed to load (zip entry not found). Root cause,
     * confirmed via runtime log: the old `path.replace("./", "")` ran BEFORE the
     * ".." collapse and matched blindly on the substring "./" wherever it appeared
     * -- including inside "../", where characters 1-2 spell exactly "./" -- so
     * "../Images" got mangled into ".Images" (the real "Images" segment eaten)
     * before the "/../" collapse loop ever got a chance to run on the (already
     * corrupted) path. Segment-based normalization sidesteps the whole class of
     * bug: split on "/", drop "." segments, pop the previous real segment on "..".
     */
    private String normalizeZipPath(String path) {
        String[] parts = path.split("/", -1);
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!stack.isEmpty()) stack.removeLast(); continue; }
            stack.addLast(part);
        }
        return String.join("/", stack);
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
        Typeface baseTf = fm.matchFamilyStyle(null, FontStyle.NORMAL);
        if (baseTf == null) {
            for (String n : new String[]{"Segoe UI", "Arial", "Helvetica", "sans-serif"}) {
                baseTf = fm.matchFamilyStyle(n, FontStyle.NORMAL);
                if (baseTf != null) break;
            }
        }
        if (baseTf == null) return;
        String familyName = baseTf.getFamilyName();

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
                if (curX > 0 || !curPage.cmds.isEmpty()) { curX = 0; curY += normalLineH; }
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
        // currentPageIndex always names the LEFT page of a 2-page spread -- keep it even
        // and in range (odd trailing "sheet" just shows a blank right page, handled at
        // render time).
        if (currentPageIndex >= currentPages.size()) currentPageIndex = Math.max(0, (currentPages.size() - 1) & ~1);
        if (currentPageIndex < 0) currentPageIndex = 0;
    }


    public class LibraryScreen extends net.minecraft.client.gui.screens.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownLib = false;
        private File selectedBook = null;
        private long lastClickTime = 0L;
        private File lastClickedFile = null;

        public LibraryScreen() { super(net.minecraft.network.chat.Component.literal("Ebook Library")); }

        @Override protected void init() { previousHudHidden = minecraft.options.hideGui; minecraft.options.hideGui = true; scanBooksIfNeeded(false); }

        @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x66000000);
            int winX = (this.width - 220) / 2; int winY = (this.height - 300) / 2;
            context.fill(winX, winY, winX + 220, winY + 300, new Color(15, 15, 15, 210).getRGB());
            context.fill(winX, winY,       winX + 220, winY + 1,   0x3CFFFFFF);
            context.fill(winX, winY + 299, winX + 220, winY + 300, 0x3CFFFFFF);
            context.fill(winX, winY,       winX + 1,   winY + 300, 0x3CFFFFFF);
            context.fill(winX + 219, winY, winX + 220, winY + 300, 0x3CFFFFFF);

            context.text(minecraft.font, "Ebook Library", winX + (220 - minecraft.font.width("Ebook Library")) / 2, winY + 10, 0xFFFFFFFF, true);
            context.fill(winX + 10, winY + 26, winX + 210, winY + 256, 0x44000000);

            int itemH = 16; int startY = winY + 28;
            context.enableScissor(winX + 10, winY + 26, winX + 210, winY + 256);
            for (int i = 0; i < availableBooks.size() && i < 14; i++) {
                File f = availableBooks.get(i); int itemY = startY + i * itemH;
                String name = f.getName().substring(0, f.getName().lastIndexOf('.') > 0 ? f.getName().lastIndexOf('.') : f.getName().length());
                boolean isSelected = (selectedBook != null && selectedBook.getAbsolutePath().equals(f.getAbsolutePath()));
                boolean isHovered  = mouseX >= winX + 10 && mouseX <= winX + 210 && mouseY >= itemY && mouseY < itemY + itemH;

                if (isSelected) context.fill(winX + 12, itemY, winX + 208, itemY + itemH - 1, new Color(255, 255, 255, 70).getRGB());
                else if (isHovered) context.fill(winX + 12, itemY, winX + 208, itemY + itemH - 1, 0x33FFFFFF);

                String ext = f.getName().toLowerCase().endsWith(".epub") ? "[EPUB] " : "[TXT] ";
                context.text(minecraft.font, ext + name, winX + 16, itemY + 4, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, true);
            }
            context.disableScissor();

            boolean hoverRead = mouseX >= winX + 15 && mouseX <= winX + 95 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 15, winY + 266, winX + 95, winY + 286, selectedBook == null ? 0xFF1A1A1A : (hoverRead ? 0xFF444444 : 0xFF222222));
            context.fill(winX + 15, winY + 266, winX + 95, winY + 267, 0x3CFFFFFF);
            context.fill(winX + 15, winY + 285, winX + 95, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 15, winY + 266, winX + 16, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 94, winY + 266, winX + 95, winY + 286, 0x3CFFFFFF);
            context.text(minecraft.font, "Read", winX + 15 + (80 - minecraft.font.width("Read")) / 2, winY + 266 + 6, selectedBook != null ? 0xFFFFFFFF : 0xFF666666, true);

            boolean hoverClose = mouseX >= winX + 125 && mouseX <= winX + 205 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 125, winY + 266, winX + 205, winY + 286, hoverClose ? 0xFF444444 : 0xFF222222);
            context.fill(winX + 125, winY + 266, winX + 205, winY + 267, 0x3CFFFFFF);
            context.fill(winX + 125, winY + 285, winX + 205, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 125, winY + 266, winX + 126, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 204, winY + 266, winX + 205, winY + 286, 0x3CFFFFFF);
            context.text(minecraft.font, "Close", winX + 125 + (80 - minecraft.font.width("Close")) / 2, winY + 266 + 6, 0xFFFFFFFF, true);

            boolean mouseDown = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (mouseDown && !wasMouseDownLib) {
                if (hoverRead && selectedBook != null) { loadBook(selectedBook); minecraft.setScreen(new ReaderScreen()); } 
                else if (hoverClose) this.onClose();
                else if (mouseX >= winX + 10 && mouseX <= winX + 210 && mouseY >= winY + 26 && mouseY <= winY + 256) {
                    int clickedIdx = (mouseY - startY) / itemH;
                    if (clickedIdx >= 0 && clickedIdx < availableBooks.size() && clickedIdx < 14) {
                        File clicked = availableBooks.get(clickedIdx);
                        long now = System.currentTimeMillis();
                        if (clicked.equals(lastClickedFile) && (now - lastClickTime) < 400) {
                            loadBook(clicked); minecraft.setScreen(new ReaderScreen());
                        } else {
                            selectedBook = clicked; lastClickedFile = clicked; lastClickTime = now;
                        }
                    }
                }
            }
            wasMouseDownLib = mouseDown;
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        @Override public void onClose() { minecraft.options.hideGui = previousHudHidden; EbookReader.this.setState(false); super.onClose(); }
        @Override public boolean isPauseScreen() { return false; }
    }

    public class ReaderScreen extends net.minecraft.client.gui.screens.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownReader = false;
        private float zoomScale = 1.0f;
        
        // --- TEXTFIELD ĐỂ NHẬP TRANG ---
        private net.minecraft.client.gui.components.EditBox pageInputWidget;
        private boolean isEditingPage = false;

        public ReaderScreen() { super(net.minecraft.network.chat.Component.literal("Ebook Reader")); }

        @Override protected void init() { 
            previousHudHidden = minecraft.options.hideGui; 
            minecraft.options.hideGui = true; 
            
            // Khởi tạo ô nhập số trang xịn xò của Minecraft
            pageInputWidget = new net.minecraft.client.gui.components.EditBox(minecraft.font, 0, 0, 40, 12, net.minecraft.network.chat.Component.literal(""));
            pageInputWidget.setMaxLength(5); // Sách 99999 trang là max
            pageInputWidget.setVisible(false);
            pageInputWidget.setBordered(true);
            // BIỂU THỨC CHÍNH QUY (REGEX): Cấm tuyệt đối số âm, số thực, chữ cái. Chỉ cho gõ số (0-9)
            pageInputWidget.setResponder(text -> {
                if (!text.isEmpty() && !text.matches("^[0-9]+$")) {
                    pageInputWidget.setValue(text.replaceAll("[^0-9]", ""));
                }
            });
            this.addRenderableWidget(pageInputWidget);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            long now = System.currentTimeMillis(); float deltaMs = lastFrameTime == 0 ? 16f : (now - lastFrameTime); lastFrameTime = now;
            if (pageFlipProgress < 1.0f) {
                pageFlipProgress += deltaMs / (float) FLIP_DURATION_MS;
                if (pageFlipProgress >= 1.0f) {
                    pageFlipProgress = 1.0f;
                    currentPageIndex = pendingSheetIndex;
                    clearFlipState();
                }
            }

            context.fill(0, 0, this.width, this.height, 0x66000000);

            // Wider than the old single-page panel -- two half-pages side by side plus a
            // spine gutter, like an open book instead of one sheet.
            float baseW = 620, baseH = 480;
            float panelW = baseW * zoomScale, panelH = baseH * zoomScale;
            float winX = (this.width - panelW) / 2f, winY = (this.height - panelH) / 2f;

            int drawX = (int) winX, drawY = (int) winY, drawW = (int) panelW, drawH = (int) panelH;

            context.fill(drawX, drawY, drawX + drawW, drawY + drawH, new Color(15, 15, 15, 210).getRGB());
            context.fill(drawX, drawY,       drawX + drawW, drawY + 1,   0x3CFFFFFF);
            context.fill(drawX, drawY + drawH - 1, drawX + drawW, drawY + drawH, 0x3CFFFFFF);
            context.fill(drawX, drawY,       drawX + 1,   drawY + drawH, 0x3CFFFFFF);
            context.fill(drawX + drawW - 1, drawY, drawX + drawW, drawY + drawH, 0x3CFFFFFF);
            // Spine shadow down the middle of the open book.
            int spineX = (int) (winX + panelW / 2f);
            context.fill(spineX - 2, drawY + 2, spineX + 2, drawY + drawH - 2, 0x33000000);

            int contentTop = (int) winY + 14;
            if (showTitle.getValue()) {
                context.text(minecraft.font, currentBookTitle, (int)(winX + (panelW - minecraft.font.width(currentBookTitle)) / 2f), contentTop, 0xFFFFFFFF, true);
                contentTop += 16;
            }

            buildFonts(readerFontSize * zoomScale);
            float gutter = 10f * zoomScale;
            float outerMargin = 18f * zoomScale;
            float halfPageW = (panelW - outerMargin * 2f - gutter) / 2f;
            float pageH = panelH - (contentTop - winY) - 40;
            layoutPagesIfNeeded(halfPageW, pageH);

            float leftX = winX + outerMargin;
            float rightX = leftX + halfPageW + gutter;

            // While flipping, keep showing the OLD sheet underneath -- the destination
            // sheet's content must not appear at all until the curl finishes (commit
            // happens above, the instant progress hits 1.0f). The single page actually
            // curling is skipped in paintSpread below (the mesh alone represents it).
            int displayLeft = currentPageIndex;
            int displayRight = displayLeft + 1;

            if (!currentPages.isEmpty()) {
                drawSpread(context, displayLeft, displayRight, leftX, rightX, contentTop, halfPageW, pageH);
            }

            // ── LOGIC HIỂN THỊ VÀ CLICK CHỌN TRANG (đếm theo tờ/sheet, 2 trang/tờ) ──
            int totalSheets = Math.max(1, (currentPages.size() + 1) / 2);
            String pageInfo = (currentPageIndex / 2 + 1) + " / " + totalSheets;
            int pageInfoW = minecraft.font.width(pageInfo);
            int pageInfoX = (int)(winX + (panelW - pageInfoW) / 2f);
            int pageInfoY = (int)(winY + panelH - 16);

            // Hover đổi màu báo hiệu cho user biết có thể click được
            boolean hoverPage = mouseX >= pageInfoX && mouseX <= pageInfoX + pageInfoW && mouseY >= pageInfoY && mouseY <= pageInfoY + 9;

            if (isEditingPage) {
                // Di chuyển Text Field đè lên đúng chỗ chữ Page đang đứng
                pageInputWidget.setX(pageInfoX + pageInfoW / 2 - 20);
                pageInputWidget.setY(pageInfoY - 2);
            } else {
                context.text(minecraft.font, pageInfo, pageInfoX, pageInfoY, hoverPage ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            }

            int btnY = (int)(winY + panelH - 30);
            int btnPrevX = (int)(winX + panelW / 2f - 26);
            int btnNextX = (int)(winX + panelW / 2f + 14);
            boolean hoverPrev = mouseX >= btnPrevX && mouseX <= btnPrevX + 16 && mouseY >= btnY && mouseY <= btnY + 16;
            boolean hoverNext = mouseX >= btnNextX && mouseX <= btnNextX + 16 && mouseY >= btnY && mouseY <= btnY + 16;

            // Prev Button
            context.fill(btnPrevX, btnY, btnPrevX + 16, btnY + 16, hoverPrev ? 0xFF444444 : 0xFF222222);
            context.fill(btnPrevX, btnY, btnPrevX + 16, btnY + 1, 0x3CFFFFFF);
            context.fill(btnPrevX, btnY + 15, btnPrevX + 16, btnY + 16, 0x3CFFFFFF);
            context.fill(btnPrevX, btnY, btnPrevX + 1, btnY + 16, 0x3CFFFFFF);
            context.fill(btnPrevX + 15, btnY, btnPrevX + 16, btnY + 16, 0x3CFFFFFF);
            context.text(minecraft.font, "<", btnPrevX + 4, btnY + 4, 0xFFFFFFFF, true);

            // Next Button
            context.fill(btnNextX, btnY, btnNextX + 16, btnY + 16, hoverNext ? 0xFF444444 : 0xFF222222);
            context.fill(btnNextX, btnY, btnNextX + 16, btnY + 1, 0x3CFFFFFF);
            context.fill(btnNextX, btnY + 15, btnNextX + 16, btnY + 16, 0x3CFFFFFF);
            context.fill(btnNextX, btnY, btnNextX + 1, btnY + 16, 0x3CFFFFFF);
            context.fill(btnNextX + 15, btnY, btnNextX + 16, btnY + 16, 0x3CFFFFFF);
            context.text(minecraft.font, ">", btnNextX + 5, btnY + 4, 0xFFFFFFFF, true);

            int zoomBtnY = (int) winY + 8;
            int zOutX = (int)(winX + panelW - 48), zInX = (int)(winX + panelW - 24);
            boolean hZOut = mouseX >= zOutX && mouseX <= zOutX + 16 && mouseY >= zoomBtnY && mouseY <= zoomBtnY + 16;
            boolean hZIn  = mouseX >= zInX  && mouseX <= zInX + 16  && mouseY >= zoomBtnY && mouseY <= zoomBtnY + 16;

            // Zoom Out Button
            context.fill(zOutX, zoomBtnY, zOutX + 16, zoomBtnY + 16, hZOut ? 0xFF444444 : 0xFF222222);
            context.fill(zOutX, zoomBtnY, zOutX + 16, zoomBtnY + 1, 0x3CFFFFFF);
            context.fill(zOutX, zoomBtnY + 15, zOutX + 16, zoomBtnY + 16, 0x3CFFFFFF);
            context.fill(zOutX, zoomBtnY, zOutX + 1, zoomBtnY + 16, 0x3CFFFFFF);
            context.fill(zOutX + 15, zoomBtnY, zOutX + 16, zoomBtnY + 16, 0x3CFFFFFF);
            context.text(minecraft.font, "-", zOutX + 6, zoomBtnY + 4, 0xFFFFFFFF, true);

            // Zoom In Button
            context.fill(zInX, zoomBtnY, zInX + 16, zoomBtnY + 16, hZIn ? 0xFF444444 : 0xFF222222);
            context.fill(zInX, zoomBtnY, zInX + 16, zoomBtnY + 1, 0x3CFFFFFF);
            context.fill(zInX, zoomBtnY + 15, zInX + 16, zoomBtnY + 16, 0x3CFFFFFF);
            context.fill(zInX, zoomBtnY, zInX + 1, zoomBtnY + 16, 0x3CFFFFFF);
            context.fill(zInX + 15, zoomBtnY, zInX + 16, zoomBtnY + 16, 0x3CFFFFFF);
            context.text(minecraft.font, "+", zInX + 5, zoomBtnY + 4, 0xFFFFFFFF, true);

            // ── BẮT TẤT CẢ CÁC LOẠI CLICK CHUỘT (TRÁI/PHẢI/GIỮA) ──
            boolean leftClick = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightClick = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            boolean midClick = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
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
                        pageInputWidget.setValue(String.valueOf(currentPageIndex / 2 + 1));
                        pageInputWidget.setVisible(true);
                        this.setFocused(pageInputWidget);
                        pageInputWidget.setFocused(true);
                    } else if (leftClick) {
                        if (hoverPrev) startFlip(-1);
                        else if (hoverNext) startFlip(1);
                        else if (hZOut) { zoomScale = Math.max(0.6f, zoomScale - 0.1f); currentLayoutW = -1; clearFlipState(); pageFlipProgress = 1f; }
                        else if (hZIn) { zoomScale = Math.min(2.0f, zoomScale + 0.1f); currentLayoutW = -1; clearFlipState(); pageFlipProgress = 1f; }
                    }
                }
            }
            wasMouseDownReader = anyClick;

            // Chốt hạ: Vẽ các component tĩnh gốc của Screen (bao gồm cả pageInputWidget)
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        /** Starts a page-turn: dir=+1 next sheet (right page curls), dir=-1 prev sheet (left page curls). No-op mid-flip or at a book edge. */
        private void startFlip(int dir) {
            if (pageFlipProgress < 1.0f) return;
            int newLeft = currentPageIndex + dir * 2;
            if (newLeft < 0 || newLeft >= currentPages.size()) return;

            int sourcePageIdx = dir > 0 ? currentPageIndex + 1 : currentPageIndex;
            FlipSide side = dir > 0 ? FlipSide.RIGHT : FlipSide.LEFT;
            Image snapshot = null;
            if (sourcePageIdx >= 0 && sourcePageIdx < currentPages.size() && currentLayoutW > 0 && currentLayoutH > 0) {
                snapshot = renderPageToImage(currentPages.get(sourcePageIdx), currentLayoutW, currentLayoutH);
            }

            clearFlipState();
            flipSourceImage = snapshot;
            flipSourceW = currentLayoutW;
            flipSourceH = currentLayoutH;
            flippingSide = side;
            flippingPageIndex = sourcePageIdx;
            pendingSheetIndex = newLeft;
            pageFlipDir = dir;
            pageFlipProgress = 0f;
        }

        private void submitPageInput() {
            try {
                int targetSheet = Integer.parseInt(pageInputWidget.getValue().trim());
                int totalSheets = Math.max(1, (currentPages.size() + 1) / 2);
                targetSheet = Math.max(1, Math.min(targetSheet, totalSheets));

                int newLeft = (targetSheet - 1) * 2;
                if (newLeft != currentPageIndex) {
                    // Direct page-jump skips the curl animation entirely (no single
                    // source page makes sense for an arbitrary multi-page jump) --
                    // snaps straight to the target sheet, same as before this feature.
                    clearFlipState();
                    currentPageIndex = newLeft;
                    pageFlipProgress = 1f;
                }
            } catch (Exception ignored) {}

            isEditingPage = false;
            pageInputWidget.setVisible(false);
        }

        /** One PIP paint covering the whole 2-page spread's bounding box (both static pages + the curling mesh, in that draw order). */
        private void drawSpread(GuiGraphicsExtractor context, int leftIdx, int rightIdx, float leftX, float rightX, float startY, float halfW, float pageH) {
            int margin = 4;
            int x0 = Math.round(leftX) - margin, y0 = Math.round(startY) - margin;
            int x1 = Math.round(rightX + halfW) + margin, y1 = Math.round(startY + pageH) + margin;
            ((GuiGraphicsExtractorAccessor) context).getGuiRenderState()
                .addPicturesInPictureState(new EbookPagePipState(
                    canvas -> paintSpread(canvas, leftIdx, rightIdx, leftX, rightX, startY, halfW, pageH), x0, y0, x1, y1));
        }

        private void paintSpread(Canvas canvas, int leftIdx, int rightIdx, float leftX, float rightX, float startY, float halfW, float pageH) {
            // The page actually curling this frame is skipped here -- the mesh below is
            // its ONLY visual representation, so nothing of the destination sheet (or a
            // stale duplicate of the departing page) can show through behind it.
            boolean leftIsFlipping = flippingSide == FlipSide.LEFT && leftIdx == flippingPageIndex;
            boolean rightIsFlipping = flippingSide == FlipSide.RIGHT && rightIdx == flippingPageIndex;
            if (!leftIsFlipping && leftIdx >= 0 && leftIdx < currentPages.size()) paintRichPage(canvas, currentPages.get(leftIdx), leftX, startY, 255);
            if (!rightIsFlipping && rightIdx >= 0 && rightIdx < currentPages.size()) paintRichPage(canvas, currentPages.get(rightIdx), rightX, startY, 255);
            if (flippingSide != null && flipSourceImage != null && !flipSourceImage.isClosed()) {
                float pageX = flippingSide == FlipSide.LEFT ? leftX : rightX;
                renderCurlMesh(canvas, flipSourceImage, flipSourceW, flipSourceH, pageX, startY, halfW, pageH, flippingSide, pageFlipProgress);
            }
        }

        /**
         * Renders a single page's content into an offscreen raster surface, snapshotted
         * once when a flip starts (see startFlip) -- the curl mesh below re-textures this
         * one static image every frame instead of re-rastering the page's text/images on
         * every frame of the animation.
         */
        private Image renderPageToImage(Page page, float w, float h) {
            int iw = Math.max(1, Math.round(w)), ih = Math.max(1, Math.round(h));
            try (Surface surface = Surface.makeRasterN32Premul(iw, ih)) {
                if (surface == null) return null;
                Canvas canvas = surface.getCanvas();
                canvas.clear(0);
                paintRichPage(canvas, page, 0, 0, 255);
                return surface.makeImageSnapshot();
            }
        }

        private static final int CURL_COLS = 24;

        /**
         * Real mesh-warp page curl (cylinder-wrap model, not a flat slide/fade): the page
         * splits at a fold line that sweeps from its outer edge to the spine as `progress`
         * goes 0->1. Columns short of the fold stay flat; columns past it wrap around an
         * imaginary cylinder of radius `pageW * CURL_RADIUS_FRAC` (`x' = foldX + R*sin(θ)`),
         * darkening toward the roll's edge-on midpoint for the shadow-gradient look. Only
         * needs 2 rows (top/bottom) since the curl amount is a pure function of the
         * horizontal distance from the spine -- every row at a given column warps
         * identically, so a full grid would just duplicate the same math per row.
         */
        private void renderCurlMesh(Canvas canvas, Image src, float srcW, float srcH, float pageX, float pageY, float pageW, float pageH, FlipSide side, float progress) {
            if (src == null || src.isClosed() || srcW <= 0 || srcH <= 0 || pageW <= 0) return;
            float radius = Math.max(1f, pageW * CURL_RADIUS_FRAC);
            float foldS = pageW * (1f - progress);

            int cols = CURL_COLS;
            Point[] positions = new Point[(cols + 1) * 2];
            Point[] texCoords = new Point[(cols + 1) * 2];
            int[] colors = new int[(cols + 1) * 2];

            for (int i = 0; i <= cols; i++) {
                float s = pageW * i / (float) cols; // distance from the spine, 0..pageW
                float u = srcW * (side == FlipSide.RIGHT ? (s / pageW) : (1f - s / pageW));

                float xLocal;
                float shade;
                if (s <= foldS) {
                    xLocal = s;
                    shade = 1f;
                } else {
                    float d = s - foldS;
                    float theta = Math.min((float) Math.PI, d / radius);
                    xLocal = foldS + radius * (float) Math.sin(theta);
                    shade = 0.25f + 0.75f * (float) Math.cos(Math.min(theta, (float) (Math.PI / 2)));
                }

                float xScreen = side == FlipSide.RIGHT ? (pageX + xLocal) : (pageX + pageW - xLocal);

                int idx = i * 2;
                positions[idx]     = new Point(xScreen, pageY);
                positions[idx + 1] = new Point(xScreen, pageY + pageH);
                texCoords[idx]     = new Point(u, 0);
                texCoords[idx + 1] = new Point(u, srcH);

                int shadeByte = Math.max(0, Math.min(255, (int) (shade * 255)));
                int argb = (255 << 24) | (shadeByte << 16) | (shadeByte << 8) | shadeByte;
                colors[idx] = argb;
                colors[idx + 1] = argb;
            }

            try (Paint paint = new Paint()) {
                paint.setShader(src.makeShader());
                canvas.drawTriangleStrip(positions, colors, texCoords, null, paint);
            }
        }

        /** Painter for drawSpread's SkiaPipState -- canvas is already in absolute GUI-logical coordinates. */
        private void paintRichPage(Canvas canvas, Page page, float startX, float startY, int alpha) {
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
        }

        // ĐÃ SỬA LẠI ĐÚNG CHUẨN KEYPRESSED CỦA MINECRAFT 1.21 ĐỂ BÀN PHÍM HOẠT ĐỘNG HOÀN HẢO
        // ĐÃ TRẢ VỀ ĐÚNG CHUẨN KEYINPUT CỦA BOZE API
        @Override 
        public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
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

            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) { startFlip(-1); return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) { startFlip(1); return true; }
            
            return super.keyPressed(input);
        }

        @Override public void onClose() { minecraft.options.hideGui = previousHudHidden; minecraft.setScreen(new LibraryScreen()); }
        @Override public boolean isPauseScreen() { return false; }
    }
}