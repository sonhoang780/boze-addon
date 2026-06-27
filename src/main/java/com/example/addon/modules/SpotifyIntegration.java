package com.example.addon.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class SpotifyIntegration extends AddonModule {
    public boolean active = false;

    // === Public state consumed by MusicHUD and PlayMusic ===
    public static volatile boolean isSpotifyPlaying   = false;
    /** True when Spotify has an active session with a track (playing OR paused). False when truly stopped/disconnected. */
    public static volatile boolean isSpotifyConnected = false;
    public static volatile String  currentTitle       = "";
    public static volatile String  currentArtist      = "";
    public static volatile String  currentAlbumArtUrl = "";
    public static volatile long    durationMs         = 0;
    public static volatile long    progressMs         = 0;
    public static volatile long    progressFetchedAt  = 0;
    public static volatile int     spotifyPlayCount   = 0;

    // Create your Spotify app at https://developer.spotify.com/dashboard
    // Add http://127.0.0.1:8888/callback as a Redirect URI in your app settings.
    // Then place your Client ID in: <minecraft_dir>/spotify_client_id.txt
    public final ToggleOption connectBtn    = new ToggleOption(this, "ConnectSpotify",    "Open browser to authenticate with your Spotify account.", false);
    public final ToggleOption disconnectBtn = new ToggleOption(this, "DisconnectSpotify", "Remove saved Spotify credentials and stop integration.", false);
    public final ToggleOption checkStatusBtn = new ToggleOption(this, "CheckStatus",      "Manual debug: call Spotify API right now and print the raw result to chat.", false);

    private static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
    private static final String SCOPE        = "user-read-currently-playing user-read-playback-state user-modify-playback-state";

    private String accessToken    = "";
    private String refreshToken   = "";
    private long   tokenExpiresAt = 0;

    private volatile boolean pollActive = false;
    private Thread pollThread;

    private String  lastTrackId               = "";
    private boolean pausedPlayMusicForSpotify = false;
    private long    lastPollErrorAt           = 0;   // throttle error messages
    private int     consecutive204            = 0;   // 204 grace: don't stop HUD on brief 204 while connected

    private static final File TOKEN_FILE     = new File(
        FabricLoader.getInstance().getGameDir().toFile(), "spotify_tokens.json");
    private static final File CLIENT_ID_FILE = new File(
        FabricLoader.getInstance().getGameDir().toFile(), "spotify_client_id.txt");

    // INSTANCE must be declared AFTER TOKEN_FILE / CLIENT_ID_FILE so that loadTokens()
    // in the constructor can access them (Java initialises static fields in declaration order).
    public static final SpotifyIntegration INSTANCE = new SpotifyIntegration();

    // Timestamp of the last "play" command sent via controlPlayPause().
    // Prevents the poll from immediately overriding the optimistic isSpotifyPlaying=true
    // before Spotify has actually had time to resume (poll fires every 2 s).
    private static volatile long lastPlayCommandSentAt = 0L;

    private final ObjectMapper mapper = new ObjectMapper();

    private SpotifyIntegration() {
        super("SpotifyIntegration", "Detects Spotify playback and overrides MusicHUD, pausing Minecraft music while Spotify is active.");
        loadTokens();
    }

    @Override
    public void onEnable() {
        this.active = true;
        if (!refreshToken.isEmpty()) {
            startPolling();
            safeInfo("Active — polling Spotify every 2 s.");
        } else {
            safeInfo("Not authenticated. Click §eConnect Spotify §ato sign in.");
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
        stopPolling();
        if (pausedPlayMusicForSpotify) resumePlayMusic();
        clearState();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (connectBtn.getValue())     { connectBtn.setValue(false);     startOAuthFlow(); }
        if (disconnectBtn.getValue())  { disconnectBtn.setValue(false);  disconnect(); }
        if (checkStatusBtn.getValue()) { checkStatusBtn.setValue(false); CompletableFuture.runAsync(this::debugCheckStatus); }
    }

    // ── OAuth PKCE ─────────────────────────────────────────────────────────────

    private void startOAuthFlow() {
        String clientId = readClientId();
        if (clientId.isEmpty()) {
            safeError("No Client ID found!");
            safeInfo("Create §e<minecraft_dir>/spotify_client_id.txt §awith your Spotify app Client ID.");
            safeInfo("Register your app at §bhttps://developer.spotify.com/dashboard §awith redirect URI: §e" + REDIRECT_URI);
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                SecureRandom rng = new SecureRandom();
                byte[] verifierBytes = new byte[64];
                rng.nextBytes(verifierBytes);
                String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);

                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                String codeChallenge = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));

                String state = Long.toHexString(rng.nextLong());

                String authUrl = "https://accounts.spotify.com/authorize"
                    + "?client_id="             + URLEncoder.encode(clientId,     StandardCharsets.UTF_8)
                    + "&response_type=code"
                    + "&redirect_uri="          + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                    + "&scope="                 + URLEncoder.encode(SCOPE,        StandardCharsets.UTF_8)
                    + "&code_challenge="        + codeChallenge
                    + "&code_challenge_method=S256"
                    + "&state="                 + state;

                net.minecraft.util.Util.getPlatform().openUri(new URI(authUrl));
                safeInfo("Browser opened — waiting for Spotify login callback...");

                String code = waitForOAuthCallback();
                if (code == null) { safeError("Authentication timed out or was cancelled."); return; }

                String body = "grant_type=authorization_code"
                    + "&code="          + URLEncoder.encode(code,         StandardCharsets.UTF_8)
                    + "&redirect_uri="  + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                    + "&client_id="     + URLEncoder.encode(clientId,     StandardCharsets.UTF_8)
                    + "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

                JsonNode res = postForm("https://accounts.spotify.com/api/token", body);
                if (res != null && res.has("access_token")) {
                    accessToken    = res.get("access_token").asText();
                    refreshToken   = res.has("refresh_token") ? res.get("refresh_token").asText() : refreshToken;
                    tokenExpiresAt = System.currentTimeMillis() + res.get("expires_in").asLong() * 1000L - 60_000L;
                    saveTokens();
                    safeInfo("§aConnected to Spotify! Listening for playback.");
                    startPolling();
                } else {
                    safeError("Token exchange failed. Check your Client ID and Redirect URI in the Spotify Developer Dashboard.");
                }
            } catch (Exception e) {
                safeError("OAuth error: " + e.getMessage());
            }
        });
    }

    private String waitForOAuthCallback() {
        try (ServerSocket server = new ServerSocket(8888)) {
            server.setSoTimeout(120_000);
            try (Socket client = server.accept()) {
                byte[] buf = new byte[4096];
                int len = client.getInputStream().read(buf);
                if (len <= 0) return null;
                String request   = new String(buf, 0, len, StandardCharsets.UTF_8);
                String firstLine = request.split("\r?\n")[0];

                String code = null;
                if (firstLine.contains("?")) {
                    String query = firstLine.split(" ")[1].split("\\?", 2)[1];
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2 && kv[0].equals("code"))
                            code = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }
                String html = code != null
                    ? "<h1>&#x2705; Spotify Connected!</h1><p>You can close this tab and return to Minecraft.</p>"
                    : "<h1>&#x274C; Authentication Failed.</h1>";
                byte[] body     = html.getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
                OutputStream os = client.getOutputStream();
                os.write(response.getBytes(StandardCharsets.UTF_8));
                os.write(body);
                os.flush();
                return code;
            }
        } catch (Exception e) { return null; }
    }

    private void refreshTokens() throws Exception {
        String clientId = readClientId();
        if (clientId.isEmpty()) throw new IOException("No Client ID configured");
        String body = "grant_type=refresh_token"
            + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
            + "&client_id="     + URLEncoder.encode(clientId,     StandardCharsets.UTF_8);
        JsonNode res = postForm("https://accounts.spotify.com/api/token", body);
        if (res == null || !res.has("access_token")) throw new IOException("Token refresh failed");
        accessToken    = res.get("access_token").asText();
        if (res.has("refresh_token")) refreshToken = res.get("refresh_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + res.get("expires_in").asLong() * 1000L - 60_000L;
        saveTokens();
    }

    // ── Polling ────────────────────────────────────────────────────────────────

    private void startPolling() {
        if (pollActive) return;
        pollActive = true;
        pollThread = new Thread(() -> {
            while (pollActive && !Thread.currentThread().isInterrupted()) {
                try {
                    if (System.currentTimeMillis() >= tokenExpiresAt) refreshTokens();
                    pollCurrentlyPlaying();
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    long now = System.currentTimeMillis();
                    if (now - lastPollErrorAt > 20_000) {
                        lastPollErrorAt = now;
                        safeError("Poll error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
            pollActive = false;
        }, "SpotifyIntegration-Poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        pollActive = false;
        if (pollThread != null) { pollThread.interrupt(); pollThread = null; }
    }

    // Called by checkStatusBtn — polls once and prints raw result to chat for debugging.
    private void debugCheckStatus() {
        try {
            if (accessToken.isEmpty() && refreshToken.isEmpty()) {
                safeError("[Debug] No tokens — click Connect Spotify first.");
                return;
            }
            if (accessToken.isEmpty() || System.currentTimeMillis() >= tokenExpiresAt) {
                safeInfo("[Debug] Token expired, refreshing...");
                refreshTokens();
            }
            URL url = new URL("https://api.spotify.com/v1/me/player/currently-playing");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            int status = conn.getResponseCode();
            safeInfo("[Debug] HTTP status: §e" + status);
            if (status == 200) {
                String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                // Show key fields without flooding chat
                JsonNode root = mapper.readTree(json);
                boolean playing  = root.path("is_playing").asBoolean(false);
                String  itemType = root.path("currently_playing_type").asText("unknown");
                String  name     = root.path("item").path("name").asText("(no name)");
                safeInfo("[Debug] is_playing=§e" + playing + " §rtype=§e" + itemType + " §rtrack=§e" + name);
            } else if (status == 204) {
                conn.disconnect();
                safeError("[Debug] 204 — Spotify sees NO active session. Open Spotify and play something, then try again.");
            } else {
                InputStream err = conn.getErrorStream();
                String body = err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "(empty)";
                conn.disconnect();
                safeError("[Debug] HTTP " + status + " → " + body.substring(0, Math.min(150, body.length())));
            }
        } catch (Exception e) {
            safeError("[Debug] Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void pollCurrentlyPlaying() throws Exception {
        URL url = new URL("https://api.spotify.com/v1/me/player/currently-playing");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);

        int status = conn.getResponseCode();
        if (status == 401) { conn.disconnect(); refreshTokens(); return; }
        if (status == 204) {
            conn.disconnect();
            consecutive204++;
            // Spotify returns 204 both when truly stopped AND briefly during pause transitions.
            // Only treat as fully stopped after 3 consecutive 204s (~6 s); a single 204 while
            // we know we're connected is treated as "paused" — keep HUD visible.
            if (consecutive204 >= 3 || !isSpotifyConnected) {
                handleSpotifyStopped();
            } else {
                isSpotifyPlaying = false;
            }
            return;
        }
        if (status != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + status); // caught by poll loop → shown in chat
        }

        consecutive204 = 0; // good 200 response — reset grace counter
        String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        if (json.isBlank()) { handleSpotifyStopped(); return; }

        JsonNode root = mapper.readTree(json);
        if (!root.has("item") || root.get("item").isNull()) { handleSpotifyStopped(); return; }

        boolean playing = root.path("is_playing").asBoolean(false);

        JsonNode item    = root.get("item");
        String  trackId  = item.path("id").asText("");
        String  title    = item.path("name").asText("Unknown");
        long    duration = item.path("duration_ms").asLong(0);
        long    progress = root.path("progress_ms").asLong(0);

        StringBuilder sb = new StringBuilder();
        JsonNode artists = item.path("artists");
        for (int i = 0; i < artists.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(artists.get(i).path("name").asText());
        }
        String artist   = sb.length() > 0 ? sb.toString() : "Unknown Artist";
        JsonNode images = item.path("album").path("images");
        String albumArt = (images.isArray() && images.size() > 0) ? images.get(0).path("url").asText("") : "";

        progressMs        = progress;
        progressFetchedAt = System.currentTimeMillis();
        durationMs        = duration;
        currentTitle      = title;
        currentArtist     = artist;
        currentAlbumArtUrl = albumArt;

        if (!trackId.equals(lastTrackId)) {
            lastTrackId = trackId;
            spotifyPlayCount++;
            String finalAlbumArt = albumArt;
            Minecraft.getInstance().execute(() -> {
                MusicHUD.INSTANCE.loadThumbnailFromUrl(finalAlbumArt);
                MusicHUD.INSTANCE.extractColorsFromUrl(finalAlbumArt);
            });
        }

        boolean wasConnected = isSpotifyConnected;
        isSpotifyConnected = true;

        if (playing) {
            if (!isSpotifyPlaying) {
                isSpotifyPlaying = true;
                if (!wasConnected) {
                    // Freshly connected from truly stopped state — pause PlayMusic
                    String detectedTitle  = title;
                    String detectedArtist = artist;
                    Minecraft.getInstance().execute(() -> {
                        pausePlayMusic();
                        safeInfo("Now playing: §e" + detectedTitle + " §7— §a" + detectedArtist);
                    });
                }
            }
        } else {
            // Track is paused — keep isSpotifyConnected=true so MusicHUD stays visible.
            // Don't flip isSpotifyPlaying→false within 3 s of a "play" command: the poll
            // can fire before Spotify has actually resumed, which would undo the optimistic
            // update and make the play button appear to do nothing.
            if (System.currentTimeMillis() - lastPlayCommandSentAt >= 3000L) {
                isSpotifyPlaying = false;
            }
        }
    }

    private void handleSpotifyStopped() {
        if (!isSpotifyPlaying && !isSpotifyConnected) return;
        isSpotifyPlaying  = false;
        isSpotifyConnected = false;
        Minecraft.getInstance().execute(() -> {
            clearState();
            if (pausedPlayMusicForSpotify) resumePlayMusic();
        });
    }

    private void pausePlayMusic() {
        if (PlayMusic.INSTANCE.active && PlayMusic.getCurrentTrack() != null && !PlayMusic.isPlayerPaused()) {
            PlayMusic.stopCurrentTrack();      // flush audio line so sound actually stops
            PlayMusic.setPausedExternal(true);
            pausedPlayMusicForSpotify = true;
        } else {
            pausedPlayMusicForSpotify = false;
        }
    }

    private void resumePlayMusic() {
        pausedPlayMusicForSpotify = false;
        // Do not auto-resume — let the user decide when to bring back Minecraft music.
    }

    private void clearState() {
        isSpotifyPlaying   = false;
        isSpotifyConnected = false;
        currentTitle       = "";
        currentArtist      = "";
        currentAlbumArtUrl = "";
        durationMs         = 0;
        progressMs         = 0;
        progressFetchedAt  = 0;
    }

    private void disconnect() {
        stopPolling();
        accessToken = ""; refreshToken = ""; tokenExpiresAt = 0; lastTrackId = "";
        if (pausedPlayMusicForSpotify) resumePlayMusic();
        clearState();
        saveTokens();
        safeInfo("Disconnected from Spotify.");
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private JsonNode postForm(String endpoint, String body) {
        try {
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.getOutputStream().write(data);
            int      status = conn.getResponseCode();
            InputStream is  = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) { conn.disconnect(); return null; }
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            return mapper.readTree(resp);
        } catch (Exception e) { return null; }
    }

    // ── Storage ────────────────────────────────────────────────────────────────

    private void loadTokens() {
        try {
            if (!TOKEN_FILE.exists()) return;
            JsonNode n = mapper.readTree(TOKEN_FILE);
            accessToken    = n.path("access_token").asText("");
            refreshToken   = n.path("refresh_token").asText("");
            tokenExpiresAt = n.path("expires_at").asLong(0);
            // Auto-start polling on game load if credentials are saved — user never has
            // to re-enable the module each session. startPolling() is a no-op if already running.
            if (!refreshToken.isEmpty()) startPolling();
        } catch (Exception ignored) {}
    }

    private void saveTokens() {
        try {
            mapper.writeValue(TOKEN_FILE, mapper.createObjectNode()
                .put("access_token",  accessToken)
                .put("refresh_token", refreshToken)
                .put("expires_at",    tokenExpiresAt));
        } catch (Exception ignored) {}
    }

    private String readClientId() {
        try {
            if (CLIENT_ID_FILE.exists()) {
                String id = Files.readString(CLIENT_ID_FILE.toPath()).trim();
                if (!id.isEmpty()) return id;
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ── Playback controls (called from MusicHUD buttons) ──────────────────────

    public static void controlPlayPause() {
        // Optimistic update so MusicHUD icon flips instantly without waiting for next poll.
        boolean wasPaused = !isSpotifyPlaying;
        isSpotifyPlaying = !isSpotifyPlaying;
        String action = wasPaused ? "play" : "pause";
        // Stamp so the poll grace period in pollCurrentlyPlaying() knows not to reset state.
        if (wasPaused) lastPlayCommandSentAt = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> INSTANCE.sendPlayerCommand("PUT", action));
    }

    public static void controlNext() {
        CompletableFuture.runAsync(() -> INSTANCE.sendPlayerCommand("POST", "next"));
    }

    public static void controlPrevious() {
        CompletableFuture.runAsync(() -> INSTANCE.sendPlayerCommand("POST", "previous"));
    }

    public static void controlSeek(long positionMs) {
        CompletableFuture.runAsync(() -> INSTANCE.sendPlayerCommand("PUT", "seek?position_ms=" + positionMs));
    }

    /** Fires a PUT or POST to /v1/me/player/<endpoint> with no request body. */
    private void sendPlayerCommand(String method, String endpoint) {
        try {
            if (accessToken.isEmpty()) return;
            if (System.currentTimeMillis() >= tokenExpiresAt) refreshTokens();
            URL url = new URL("https://api.spotify.com/v1/me/player/" + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            // Java's HttpURLConnection requires setDoOutput(true) for PUT to actually
            // send the request — setDoOutput(false) on PUT can cause the JVM to silently
            // downgrade or Spotify to reject with 4xx. Write empty body to force it.
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Length", "0");
            conn.getOutputStream().close();
            int status = conn.getResponseCode();
            conn.disconnect();
            if (status == 401) refreshTokens();
        } catch (Exception ignored) {}
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private void safeInfo(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.execute(() -> mc.player.sendSystemMessage(Component.literal("§2[Spotify] §a" + msg)));
    }

    private void safeError(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.execute(() -> mc.player.sendSystemMessage(Component.literal("§2[Spotify] §c" + msg)));
    }
}
