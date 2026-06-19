package com.example.addon.modules;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.Option;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.*;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayMusic extends AddonModule {
    public static final PlayMusic INSTANCE = new PlayMusic();
    public boolean active = false;

    // ── Đọc ghi file kingthon_prefix.txt ──
    public static String CHAT_PREFIX = ">";
    private static final File PREFIX_FILE = new File(FabricLoader.getInstance().getGameDir().toFile(), "kingthon_prefix.txt");

    static {
        try {
            if (PREFIX_FILE.exists()) {
                String content = Files.readString(PREFIX_FILE.toPath()).trim();
                if (!content.isEmpty() && content.length() <= 3) CHAT_PREFIX = content;
            }
        } catch (Exception ignored) {}
    }

    // ── OPTIONS (ALL DESCRIPTIONS IN ENGLISH) ──
    public final ToggleOption openGuiBtn  = new ToggleOption(this, "🎵 Open Music UI", "Click to open the music control interface.", false);
    public final ToggleOption chatSearch  = new ToggleOption(this, "🔍 Chat Search", "Enable searching music and showing live suggestions via chat.", true);
    public final SliderOption volume      = new SliderOption(this, "Volume", "Adjust the media playback volume.", 50.0, 0.0, 100.0, 1.0);
    public final ToggleOption previousBtn = new ToggleOption(this, "Previous", "Play the previous track from history.", false);
    public final ToggleOption togglePause = new ToggleOption(this, "Play / Pause", "Pause or resume current track playback.", false);
    public final ToggleOption nextBtn     = new ToggleOption(this, "Next", "Skip the current track.", false);
    public final ToggleOption loopCurrent = new ToggleOption(this, "Loop", "Loop the currently playing track infinitely.", false);
    public final ToggleOption autoPlay    = new ToggleOption(this, "Auto Play Next", "Automatically queue related tracks when empty.", true);
    public final ToggleOption logoutBtn   = new ToggleOption(this, "Logout YouTube", "Log out of your YouTube account from the client.", false);

    private static AudioPlayerManager playerManager;
    private static AudioPlayer player;
    private static TrackScheduler scheduler;
    private static StreamPlayer streamPlayer;
    private static Thread soundThread;
    private static YoutubeAudioSourceManager ytSourceManager;
    
    public static int playCount = 0; 
    
    private static final Set<String> playedHistory = Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 100; }
        }
    );
    
    private final List<String> currentSuggestions = new CopyOnWriteArrayList<>();
    private String lastQuery = "";
    private String pendingQuery = "";
    private int suggestionCooldown = 0; 
    
    private boolean wasMouseDown = false;
    private boolean wasTabDown = false;
    
    // Biến theo dõi Animation (Lerp) của khung Suggestion
    private double animSuggestHeight = 0.0;
    
    public static volatile float currentAmplitude = 0f;
    private static boolean consoleHooked = false;

    private PlayMusic() {
        super("PlayMusic", "Direct YouTube music player with chat suggestions.");

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (this.active && chatSearch.getValue() && message.startsWith(CHAT_PREFIX)) {
                String query = message.substring(CHAT_PREFIX.length()).trim();
                
                if (query.toLowerCase().startsWith("prefix")) {
                    String newPrefix = query.substring(6).trim();
                    if (!newPrefix.isEmpty() && newPrefix.length() <= 3) {
                        CHAT_PREFIX = newPrefix;
                        try { Files.writeString(PREFIX_FILE.toPath(), CHAT_PREFIX); } catch (Exception ignored) {}
                        safeInfo("Chat Prefix has been changed to: §e" + CHAT_PREFIX);
                    } else safeError("Invalid prefix! Use 1 to 3 characters.");
                    return false; 
                }

                // ── LỆNH LOGIN YOUTUBE ──
                if (query.toLowerCase().equals("login")) {
                    safeInfo("§e[YouTube Login] §fInitiating login process...");
                    safeInfo("§fOpening browser to §bhttps://www.google.com/device §f...");
                    try {
                        if (ytSourceManager != null) {
                            ytSourceManager.useOauth2(null, true);
                        }
                        net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI("https://www.google.com/device"));
                    } catch (Exception e) {
                        safeError("Failed to open browser.");
                    }
                    currentSuggestions.clear();
                    lastQuery = "";
                    pendingQuery = "";
                    return false;
                }

                if (!query.isEmpty()) {
                    if (SpotifyIntegration.isSpotifyPlaying) {
                        safeError("You have to turn off Spotify first!");
                    } else {
                        searchAndPlay(query);
                    }
                } else safeError("Search query cannot be empty! Type §e" + CHAT_PREFIX + "prefix <new_prefix> §cto change prefix.");
                currentSuggestions.clear();
                lastQuery = "";
                pendingQuery = "";
                return false; 
            }
            return true;
        });

         HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!this.active || !chatSearch.getValue() || mc.currentScreen == null) return;
            
            if (mc.currentScreen instanceof ChatScreen) {
                int hudX = (int)(double) MusicHUD.INSTANCE.posX;
                int hudY = (int)(double) MusicHUD.INSTANCE.posY + 72; // 70 là chiều cao HUD + 2 đệm
                int boxW = 320;
                int itemH = 14;
                
                // Tính toán chiều cao mục tiêu cho Lerp
                double targetHeight = currentSuggestions.isEmpty() ? 0 : (currentSuggestions.size() * itemH + 4);
                animSuggestHeight += (targetHeight - animSuggestHeight) * 0.15; // Tốc độ trượt mượt mà
                
                if (animSuggestHeight >= 1.0) {
                    context.fill(hudX, hudY, hudX + boxW, hudY + (int)animSuggestHeight, 0xEE111111);
                    context.enableScissor(hudX, hudY, hudX + boxW, hudY + (int)animSuggestHeight);

                    double scale = mc.getWindow().getScaleFactor();
                    double mx = mc.mouse.getX() / scale;
                    double my = mc.mouse.getY() / scale;

                    for (int i = 0; i < currentSuggestions.size(); i++) {
                        int itemY = hudY + 2 + (i * itemH);
                        boolean isHovering = mx >= hudX && mx <= hudX + boxW && my >= itemY && my < itemY + itemH;
                        if (isHovering) {
                            context.fill(hudX, itemY, hudX + boxW, itemY + itemH, 0xFF333333);
                        }
                        context.drawText(mc.textRenderer, "» " + currentSuggestions.get(i), hudX + 6, itemY + 3, 0xFF00FFBB, true);
                    }
                    context.disableScissor();
                }
            } else {
                animSuggestHeight = 0.0;
            }
        });
    }

    // ── KẸP CONSOLE ĐỂ BẮT MÃ LOGIN HIỂN THỊ LÊN CHAT ──
    private void hookConsoleForOauth() {
        if (consoleHooked) return;
        consoleHooked = true;
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            @Override
            public void write(int b) {
                buffer.write(b);
                if (b == '\n') {
                    String line = buffer.toString();
                    if (line.contains("https://www.google.com/device") && line.contains("code")) {
                        Matcher m = Pattern.compile("code\\s+([A-Z0-9-]+)").matcher(line);
                        if (m.find()) {
                            String code = m.group(1);
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player != null) {
                                mc.execute(() -> {
                                    safeInfo("§e========================================");
                                    safeInfo("§a[YouTube Auth] §fAction required!");
                                    safeInfo("§fYour Login Code is: §b§l" + code);
                                    safeInfo("§fPlease enter it in the opened browser window.");
                                    safeInfo("§e========================================");
                                });
                            }
                        }
                    }
                    if (line.toLowerCase().contains("refresh token has been successfully") || line.toLowerCase().contains("successfully updated")) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player != null) {
                            mc.execute(() -> safeInfo("§a[YouTube Auth] §fLogin successful! You can now play any track."));
                        }
                    }
                    originalOut.print(line);
                    buffer.reset();
                }
            }
        }));
    }

    public static AudioTrack getCurrentTrack() { return player != null ? player.getPlayingTrack() : null; }
    public static boolean isPlayerPaused() { return player != null && player.isPaused(); }
    public static void seekTo(long positionMs) {
        if (player != null && player.getPlayingTrack() != null) {
            player.getPlayingTrack().setPosition(Math.max(0, Math.min(positionMs, player.getPlayingTrack().getDuration())));
        }
    }

    public static void setPausedExternal(boolean paused) {
        if (player != null) player.setPaused(paused);
    }

    @Override
    public void onEnable() {
        this.active = true;
        // Bọc toàn bộ khởi tạo nặng trong try/catch: onEnable() có thể được gọi RẤT SỚM
        // lúc nạp config (trước khi client sẵn sàng). Nếu nó ném lỗi, vòng nạp module của
        // Boze có thể coi module này hỏng và để nó về TẮT. Nuốt lỗi để state luôn giữ.
        try {
            initAudioEngine();
        } catch (Throwable t) {
            System.err.println("[PlayMusic] onEnable init failed (deferred): " + t);
        }
    }

    private void initAudioEngine() {
        hookConsoleForOauth();

        if (playerManager == null) {
            playerManager = new DefaultAudioPlayerManager();
            playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
            // Khi Pause ta NGỪNG đọc stream (không gọi provide), nên tắt cơ chế tự dọn track
            // "không được truy vấn" của lavaplayer — nếu không, dừng lâu sẽ bị tự stop track.
            playerManager.setPlayerCleanupThreshold(Long.MAX_VALUE);
            ytSourceManager = new YoutubeAudioSourceManager(true, new dev.lavalink.youtube.clients.Music(), new dev.lavalink.youtube.clients.TvHtml5Simply(), new dev.lavalink.youtube.clients.AndroidVr(), new dev.lavalink.youtube.clients.Web());
            
            String token = readToken();
            if (token.isEmpty()) { 
                ytSourceManager.useOauth2(null, false); 
                // ── HƯỚNG DẪN CHO NGƯỜI MỚI DÙNG LẦN ĐẦU ──
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null) {
                        mc.execute(() -> {
                            safeInfo("§e========================================");
                            safeInfo("§bWelcome to Music HUD!");
                            safeInfo("§fTo play age-restricted or premium tracks, you need to log in.");
                            safeInfo("§fType §a" + CHAT_PREFIX + "login §fin chat to authenticate with YouTube.");
                            safeInfo("§e========================================");
                        });
                    }
                });
            } 
            else { ytSourceManager.useOauth2(token, true); }
            
            playerManager.registerSourceManager(ytSourceManager);
            AudioSourceManagers.registerRemoteSources(playerManager, com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);

            player = playerManager.createPlayer();
            player.setVolume(volume.getValue().intValue());
            scheduler = new TrackScheduler();
            player.addListener(scheduler);
        }
        if (streamPlayer == null || !streamPlayer.playing) {
            streamPlayer = new StreamPlayer();
            soundThread = new Thread(streamPlayer, "KingThon-Audio-Thread");
            soundThread.setDaemon(true); soundThread.start();
        } else if (player != null && player.isPaused()) player.setPaused(false);
    }

    // FIX: mỗi khi khởi động lại Minecraft, PlayMusic bị tắt.
    // Nguyên nhân: AddonModule.fromJson() gọi object.get(name).getAsJsonObject() — nếu
    // có option MỚI chưa có trong config cũ (vd: option "BackGround" thêm vào MusicHUD)
    // thì ném NullPointerException, làm hỏng vòng nạp module và các module đăng ký SAU
    // (PlayMusic nằm ngay sau MusicHUD) bị về mặc định = tắt.
    // Override này bỏ qua option thiếu/khác kiểu một cách an toàn nên state luôn được nạp.
    @Override
    public AddonModule fromJson(JsonObject object) {
        try {
            if (object.has("title"))            setTitle(object.get("title").getAsString());
            if (object.has("state"))            setState(object.get("state").getAsBoolean());
            if (object.has("visible"))          setVisible(object.get("visible").getAsBoolean());
            if (object.has("notify"))           setNotify(object.get("notify").getAsBoolean());
            if (object.has("onlyWhileHolding")) setOnlyWhileHolding(object.get("onlyWhileHolding").getAsBoolean());
            for (Option<?> setting : options) {
                try {
                    JsonElement el = object.get(setting.name);
                    if (el != null && el.isJsonObject()) setting.fromJson(el.getAsJsonObject());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return this;
    }

    @Override
    public void onDisable() {
        this.active = false;
        currentSuggestions.clear();
        animSuggestHeight = 0.0;
        if (streamPlayer != null) {
            streamPlayer.stop = true;
            if (streamPlayer.line != null) streamPlayer.line.flush();
            streamPlayer = null;
        }
        if (soundThread != null && soundThread.isAlive()) soundThread.interrupt();
        if (player != null) player.stopTrack();
    }

    public static void stopCurrentTrack() {
        if (player != null) player.stopTrack();
        if (streamPlayer != null && streamPlayer.line != null) streamPlayer.line.flush();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        long win = mc.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean tabDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
        
        boolean justClicked = mouseDown && !wasMouseDown;
        boolean justTabbed = tabDown && !wasTabDown;
        
        wasMouseDown = mouseDown;
        wasTabDown = tabDown;

        if (this.active && chatSearch.getValue() && mc.currentScreen instanceof ChatScreen) {
            TextFieldWidget chatField = null;
            
            for (net.minecraft.client.gui.Element e : mc.currentScreen.children()) {
                if (e instanceof TextFieldWidget tf) {
                    chatField = tf;
                    break;
                }
            }

            if (chatField != null) {
                String text = chatField.getText();
                
                if (text.startsWith(CHAT_PREFIX)) {
                    String query = text.substring(CHAT_PREFIX.length()).trim();

                    if (justTabbed && !currentSuggestions.isEmpty()) {
                        chatField.setText(CHAT_PREFIX + currentSuggestions.get(0) + " ");
                        currentSuggestions.clear();
                        lastQuery = chatField.getText();
                        pendingQuery = "";
                        suggestionCooldown = 0;
                    } 
                    else if (justClicked && !currentSuggestions.isEmpty()) {
                        double scale = mc.getWindow().getScaleFactor();
                        double mx = mc.mouse.getX() / scale;
                        double my = mc.mouse.getY() / scale;

                        int hudX = (int)(double) MusicHUD.INSTANCE.posX;
                        int hudY = (int)(double) MusicHUD.INSTANCE.posY + 72;
                        int boxW = 320;
                        int itemH = 14;

                        if (mx >= hudX && mx <= hudX + boxW && my >= hudY && my < hudY + currentSuggestions.size() * itemH) {
                            int clickedIndex = (int) ((my - hudY - 2) / itemH);
                            if (clickedIndex >= 0 && clickedIndex < currentSuggestions.size()) {
                                String selectedQuery = currentSuggestions.get(clickedIndex);
                                searchAndPlay(selectedQuery);
                                currentSuggestions.clear();
                                lastQuery = "";
                                pendingQuery = "";
                                suggestionCooldown = 0;
                                mc.setScreen(null);
                            }
                        }
                    }

                    if (!text.equals(lastQuery)) {
                        lastQuery = text;
                        pendingQuery = query;
                        suggestionCooldown = 12; 
                    }
                } else {
                    currentSuggestions.clear();
                    lastQuery = text;
                    pendingQuery = "";
                }
            }
            
            if (suggestionCooldown > 0) {
                suggestionCooldown--;
                if (suggestionCooldown == 0 && !pendingQuery.isEmpty()) {
                    fetchYouTubeSuggestions(pendingQuery);
                }
            }
        } else {
            if (!currentSuggestions.isEmpty()) currentSuggestions.clear();
            lastQuery = "";
            pendingQuery = "";
            suggestionCooldown = 0;
        }

        if (openGuiBtn.getValue()) {
            openGuiBtn.setValue(false);
            mc.execute(() -> mc.setScreen(new MusicScreen())); // Make sure MusicScreen exists or remove this if broken
        }
        if (player != null && player.getVolume() != volume.getValue().intValue()) player.setVolume(volume.getValue().intValue());
        
        if (togglePause.getValue()) {
            togglePause.setValue(false); 
            if (player != null) player.setPaused(!player.isPaused());
        }
        if (nextBtn.getValue()) {
            nextBtn.setValue(false); 
            if (scheduler != null) scheduler.nextTrack();
        }
        if (previousBtn.getValue()) {
            previousBtn.setValue(false); 
            if (scheduler != null) scheduler.previousTrack();
        }
        if (logoutBtn.getValue()) {
            logoutBtn.setValue(false);
            saveToken(""); if (playerManager != null) { playerManager.shutdown(); playerManager = null; }
            if (streamPlayer != null) { streamPlayer.stop = true; streamPlayer = null; }
            playedHistory.clear(); safeInfo("Logged out successfully!");
        }
    }

    private void fetchYouTubeSuggestions(String query) {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + URLEncoder.encode(query, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                InputStream in = conn.getInputStream();
                String json = new String(in.readAllBytes(), "UTF-8");
                in.close(); conn.disconnect();

                int secondArrayStart = json.indexOf(",[");
                if (secondArrayStart != -1) {
                    String suggestionsPart = json.substring(secondArrayStart + 2);
                    List<String> list = new ArrayList<>();
                    
                    Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(suggestionsPart);
                    while (matcher.find()) {
                        String clean = matcher.group(1).trim();
                        if (!clean.isEmpty()) {
                            list.add(clean);
                        }
                        if (list.size() >= 5) break; 
                    }
                    
                    currentSuggestions.clear();
                    currentSuggestions.addAll(list);
                }
            } catch(Exception e) {
                currentSuggestions.clear();
            }
        });
    }

    private void searchAndPlay(String keyword) {
        if (playerManager == null || keyword.trim().isEmpty()) return;
        safeInfo("Searching: " + keyword + "...");
        String query = keyword.startsWith("http") ? keyword : "ytsearch:" + keyword;
        
        playerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { forcePlayInstantly(track); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { 
                if (playlist.getTracks().isEmpty()) return;
                if (playlist.isSearchResult()) { forcePlayInstantly(playlist.getTracks().get(0)); return; }
                safeInfo("Loaded playlist: §e" + playlist.getName() + " §a(" + playlist.getTracks().size() + " tracks)");
                if (scheduler != null) scheduler.queue.clear();
                forcePlayInstantly(playlist.getTracks().get(0));
                if (scheduler != null) {
                    for (int i = 1; i < playlist.getTracks().size(); i++) scheduler.queue.add(playlist.getTracks().get(i));
                }
            }
            @Override public void noMatches() { safeError("No matches found!"); }
            @Override public void loadFailed(FriendlyException e) { safeError("Load failed: " + e.getMessage()); }
        });
    }

    public void searchAndPlayFromGUI(String keyword) {
        if (!this.active) { safeError("PlayMusic module must be enabled first!"); return; }
        searchAndPlay(keyword);
    }

    public void forcePlayInstantly(AudioTrack track) {
        if (scheduler != null) scheduler.queue.clear();
        if (player != null) { player.setPaused(false); player.startTrack(track, false); }
        
        if (scheduler != null) scheduler.historyQueue.add(track.makeClone());
        if (scheduler != null && scheduler.historyQueue.size() > 50) scheduler.historyQueue.remove(0);

        String id = track.getIdentifier();
        playedHistory.remove(id); playedHistory.add(id);
        
        playCount++; 
        safeInfo("Now playing: " + track.getInfo().title);
    }

    private String readToken() { try { File f = new File(FabricLoader.getInstance().getGameDir().toFile(), "kingthon_token.txt"); if (f.exists()) return new String(Files.readAllBytes(f.toPath())).trim(); } catch (Exception e) {} return ""; }
    private void saveToken(String token) { try { File f = new File(FabricLoader.getInstance().getGameDir().toFile(), "kingthon_token.txt"); Files.write(f.toPath(), token.getBytes()); } catch (Exception e) {} }
    public void safeInfo(String msg) { MinecraftClient mc = MinecraftClient.getInstance(); if (mc.player != null) mc.execute(() -> mc.player.sendMessage(Text.literal("§d[Music] §a" + msg), false)); }
    public void safeError(String msg) { MinecraftClient mc = MinecraftClient.getInstance(); if (mc.player != null) mc.execute(() -> mc.player.sendMessage(Text.literal("§d[Music] §c" + msg), false)); }

    private class TrackScheduler extends AudioEventAdapter {
        public final List<AudioTrack> queue = new ArrayList<>();
        public final List<AudioTrack> historyQueue = new ArrayList<>(); 
        
        public void nextTrack() {
            if (!queue.isEmpty()) { 
                forcePlayInstantly(queue.remove(0)); 
            } else if (autoPlay.getValue() && player.getPlayingTrack() != null) {
                String currentId = player.getPlayingTrack().getIdentifier();
                player.stopTrack(); 
                safeInfo("Loading recommendations from YouTube Music...");
                loadAutoMix(currentId);
            } else { 
                player.stopTrack(); 
                safeInfo("Queue is empty."); 
            }
        }

        public void previousTrack() {
            if (historyQueue.size() >= 2) {
                historyQueue.remove(historyQueue.size() - 1); 
                AudioTrack previousTrack = historyQueue.remove(historyQueue.size() - 1); 
                forcePlayInstantly(previousTrack); 
            } else {
                safeInfo("No previous tracks found in history!");
            }
        }
        
        @Override
        public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
            // An toàn kép: nếu đang Pause thì tuyệt đối không tự chuyển bài.
            if (player.isPaused()) return;
            if (endReason.mayStartNext) {
                if (loopCurrent.getValue()) {
                    player.startTrack(track.makeClone(), false);
                } else if (!queue.isEmpty()) {
                    nextTrack();
                } else if (autoPlay.getValue()) {
                    loadAutoMix(track.getIdentifier());
                }
            }
        }

        private void loadAutoMix(String trackId) {
            String mixUrl = "https://music.youtube.com/watch?v=" + trackId + "&list=RDAMVM" + trackId;
            playerManager.loadItem(mixUrl, new AudioLoadResultHandler() {
                @Override public void trackLoaded(AudioTrack t) { forcePlayInstantly(t); }
                @Override public void playlistLoaded(AudioPlaylist playlist) {
                    for (AudioTrack t : playlist.getTracks()) {
                        String nextId = t.getIdentifier();
                        if (!nextId.equals(trackId) && !playedHistory.contains(nextId)) { 
                            forcePlayInstantly(t); return; 
                        }
                    }
                }
                @Override public void noMatches() {}
                @Override public void loadFailed(FriendlyException e) {}
            });
        }
    }

    private class StreamPlayer implements Runnable {
        public boolean stop = false, playing = false; public SourceDataLine line;
        @Override public void run() {
            AudioDataFormat format = new Pcm16AudioDataFormat(2, 44100, StandardAudioDataFormats.COMMON_PCM_S16_BE.chunkSampleCount, true);
            AudioInputStream stream = AudioPlayerInputStream.createStream(player, format, 10000L, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, stream.getFormat());
            try {
                line = (SourceDataLine) AudioSystem.getLine(info); line.open(stream.getFormat()); line.start(); playing = true;
                byte[] buffer = new byte[StandardAudioDataFormats.COMMON_PCM_S16_BE.maximumChunkSize()]; int chunkSize;
                
                while (!stop) {
                    // FIX "đang dừng mà tự chuyển bài": KHÔNG đọc stream khi đang Pause.
                    // Trước đây stream.read() nằm trong điều kiện while nên VẪN chạy lúc pause,
                    // ngốn dần các frame (kể cả frame im lặng) → track bị đẩy tới cuối → onTrackEnd
                    // → autoplay nhảy bài dù người dùng đang dừng. Giờ pause = ngừng tiêu thụ track.
                    if (player != null && player.isPaused()) {
                        PlayMusic.currentAmplitude = 0f;
                        Thread.sleep(10);
                        continue;
                    }

                    chunkSize = stream.read(buffer);
                    if (chunkSize == -1) break;
                    if (chunkSize == 0) {
                        PlayMusic.currentAmplitude = 0f;
                        Thread.sleep(10);
                        continue;
                    }

                    int maxSample = 0;
                    for (int i = 0; i < chunkSize - 1; i += 2) {
                        short sample = (short) ((buffer[i] << 8) | (buffer[i + 1] & 0xFF));
                        int absSample = Math.abs((int)sample); if (absSample > maxSample) maxSample = absSample;
                    }
                    float rawPeak = maxSample / 32768.0f; float currentVol = player.getVolume() / 100.0f;
                    if (currentVol > 0.05f) rawPeak = rawPeak / currentVol; 
                    PlayMusic.currentAmplitude = Math.min(1.0f, rawPeak * 1.8f);
                    
                    // Chỉ đẩy ra loa khi không Pause
                    line.write(buffer, 0, chunkSize);
                }
            } catch (Exception e) {} finally { PlayMusic.currentAmplitude = 0f; if (line != null) { line.drain(); line.stop(); line.close(); } playing = false; }
        }
    }
}