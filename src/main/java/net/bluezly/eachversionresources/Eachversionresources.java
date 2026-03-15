package net.bluezly.eachversionresources;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Eachversionresources extends JavaPlugin implements Listener {

    private final Map<String, Integer> addressProtocolMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerProtocolMap = new ConcurrentHashMap<>();
    private final Map<Integer, String> protocolNameMap = new ConcurrentHashMap<>();

    private File cacheFile;

    private String remoteVersionsUrl;
    private long refreshHours;
    private boolean debug;
    private boolean fallbackEnabled;
    private boolean sendOnJoin;
    private boolean useDefaultPack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalSettings();

        cacheFile = new File(getDataFolder(), "versions-cache.json");

        loadVersionsRegistry();
        Bukkit.getPluginManager().registerEvents(this, this);

        try {
            injectServerChannels();
            debug("Handshake detector injected successfully.");
        } catch (Throwable t) {
            getLogger().severe("Failed to inject handshake detector: " + t.getMessage());
            t.printStackTrace();
        }

        long refreshTicks = Math.max(20L * 60L, 20L * 60L * 60L * refreshHours);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                loadVersionsRegistry();
            } catch (Throwable t) {
                getLogger().warning("Scheduled versions refresh failed: " + t.getMessage());
            }
        }, refreshTicks, refreshTicks);

        getLogger().info("EachVersionResources enabled. Loaded " + protocolNameMap.size() + " protocol mappings.");
    }

    @Override
    public void onDisable() {
        getLogger().info("EachVersionResources disabled.");
    }

    private void reloadLocalSettings() {
        reloadConfig();

        remoteVersionsUrl = getConfig().getString(
                "versions-url",
                "https://raw.githubusercontent.com/Bluezly/iconforgrowstock/refs/heads/main/versions.json"
        );
        refreshHours = getConfig().getLong("refresh-hours", 6L);
        debug = getConfig().getBoolean("debug", true);
        fallbackEnabled = getConfig().getBoolean("fallback.enabled", true);
        sendOnJoin = getConfig().getBoolean("send-pack-on-join", true);
        useDefaultPack = getConfig().getBoolean("default-pack.enabled", false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String key = normalize(player.getAddress());
        Integer protocol = addressProtocolMap.remove(key);

        if (protocol != null) {
            playerProtocolMap.put(player.getUniqueId(), protocol);
            debug(player.getName() + " joined with protocol " + protocol + " -> " + resolveVersionName(protocol));
        } else {
            debug("Protocol not captured for " + player.getName());
        }

        if (sendOnJoin) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    sendConfiguredPack(player);
                } catch (Throwable t) {
                    getLogger().warning("Failed to send pack to " + player.getName() + ": " + t.getMessage());
                }
            }, 20L);
        }
    }

    private void sendConfiguredPack(Player player) {
        Integer protocol = playerProtocolMap.get(player.getUniqueId());
        if (protocol == null) {
            debug("No protocol known yet for " + player.getName());
            return;
        }

        String versionName = resolveVersionName(protocol);
        ConfigurationSection packSection = getPackSectionForVersion(versionName);

        if (packSection == null) {
            debug("No configured pack for version " + versionName + " and no default pack for " + player.getName());
            return;
        }

        if (!packSection.getBoolean("enabled", false)) {
            debug("Pack section exists but disabled for version " + versionName);
            return;
        }

        String url = packSection.getString("url", "").trim();
        String sha1 = packSection.getString("sha1", "").trim();
        boolean required = packSection.getBoolean("required", false);
        String prompt = packSection.getString("prompt", "");

        if (url.isEmpty()) {
            debug("Configured pack for version " + versionName + " has empty url.");
            return;
        }

        sendResourcePack(player, url, sha1, required, prompt);
        debug("Sent configured pack to " + player.getName() + " for version " + versionName);
    }

    private ConfigurationSection getPackSectionForVersion(String versionName) {
        ConfigurationSection packsSection = getConfig().getConfigurationSection("packs");
        if (packsSection != null) {
            ConfigurationSection direct = packsSection.getConfigurationSection(versionName);
            if (direct != null) {
                return direct;
            }
        }

        if (useDefaultPack) {
            return getConfig().getConfigurationSection("default-pack");
        }

        return null;
    }

    private void sendResourcePack(Player player, String url, String sha1, boolean required, String prompt) {
        try {
            try {
                Method modern = player.getClass().getMethod(
                        "setResourcePack",
                        String.class,
                        String.class,
                        boolean.class
                );
                modern.invoke(player, url, sha1, required);
                return;
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method modernWithPrompt = player.getClass().getMethod(
                        "setResourcePack",
                        String.class,
                        String.class,
                        boolean.class,
                        Class.forName("net.kyori.adventure.text.Component")
                );
                modernWithPrompt.invoke(player, url, sha1, required, null);
                return;
            } catch (Throwable ignored) {
            }

            try {
                Method older = player.getClass().getMethod(
                        "setResourcePack",
                        String.class,
                        byte[].class,
                        String.class,
                        boolean.class
                );
                older.invoke(player, url, parseSha1Bytes(sha1), prompt, required);
                return;
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method older2 = player.getClass().getMethod(
                        "setResourcePack",
                        String.class,
                        byte[].class
                );
                older2.invoke(player, url, parseSha1Bytes(sha1));
                return;
            } catch (NoSuchMethodException ignored) {
            }

            player.setResourcePack(url);
        } catch (Throwable t) {
            throw new RuntimeException("Could not send resource pack: " + t.getMessage(), t);
        }
    }

    private byte[] parseSha1Bytes(String sha1) {
        String clean = sha1 == null ? "" : sha1.trim().toLowerCase(Locale.ROOT);
        if (clean.length() != 40) {
            return new byte[0];
        }

        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            int index = i * 2;
            result[i] = (byte) Integer.parseInt(clean.substring(index, index + 2), 16);
        }
        return result;
    }

    private void loadVersionsRegistry() {
        try {
            String json = downloadText(remoteVersionsUrl, 10000, 15000);
            if (json != null && !json.isBlank()) {
                parseProtocolMap(json);
                writeCache(json);
                getLogger().info("Loaded versions registry from remote URL.");
                return;
            }
        } catch (Throwable t) {
            getLogger().warning("Remote versions load failed: " + t.getMessage());
        }

        try {
            if (cacheFile.exists()) {
                String json = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
                parseProtocolMap(json);
                getLogger().info("Loaded versions registry from local cache.");
                return;
            }
        } catch (Throwable t) {
            getLogger().warning("Cache versions load failed: " + t.getMessage());
        }

        if (fallbackEnabled) {
            loadMinimalFallback();
            getLogger().warning("Using minimal built-in fallback mappings.");
        } else {
            protocolNameMap.clear();
            getLogger().warning("No versions registry available and fallback disabled.");
        }
    }

    private void parseProtocolMap(String json) {
        Map<Integer, String> parsed = parseByProtocol(json);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("No protocol mappings found in JSON.");
        }

        protocolNameMap.clear();
        protocolNameMap.putAll(parsed);
    }

    private Map<Integer, String> parseByProtocol(String json) {
        Map<Integer, String> map = new HashMap<>();

        Pattern blockPattern = Pattern.compile("\"by_protocol\"\\s*:\\s*\\{([\\s\\S]*?)\\}\\s*(,|})");
        Matcher blockMatcher = blockPattern.matcher(json);
        if (!blockMatcher.find()) {
            return map;
        }

        String body = blockMatcher.group(1);
        Pattern pairPattern = Pattern.compile("\"(\\d+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pairMatcher = pairPattern.matcher(body);

        while (pairMatcher.find()) {
            int protocol = Integer.parseInt(pairMatcher.group(1));
            String versionName = pairMatcher.group(2);
            map.put(protocol, versionName);
        }

        return map;
    }

    private void writeCache(String json) {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            Files.writeString(cacheFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Failed to write cache: " + e.getMessage());
        }
    }

    private void loadMinimalFallback() {
        protocolNameMap.clear();
        protocolNameMap.put(763, "1.20-1.20.1");
        protocolNameMap.put(764, "1.20.2");
        protocolNameMap.put(765, "1.20.3-1.20.4");
        protocolNameMap.put(766, "1.20.5-1.20.6");
        protocolNameMap.put(767, "1.21-1.21.1");
        protocolNameMap.put(768, "1.21.2-1.21.3");
        protocolNameMap.put(769, "1.21.4");
        protocolNameMap.put(770, "1.21.5");
        protocolNameMap.put(771, "1.21.6");
        protocolNameMap.put(772, "1.21.7-1.21.8");
        protocolNameMap.put(773, "1.21.9-1.21.10");
        protocolNameMap.put(774, "1.21.11");
    }

    private String resolveVersionName(int protocol) {
        return protocolNameMap.getOrDefault(protocol, "unknown(" + protocol + ")");
    }

    private String downloadText(String url, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("User-Agent", "EachVersionResources/1.0");
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");

        int code = connection.getResponseCode();
        if (code < 200 || code > 299) {
            throw new IOException("HTTP " + code);
        }

        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void injectServerChannels() throws Exception {
        Object craftServer = Bukkit.getServer();
        Method getServerMethod = craftServer.getClass().getMethod("getServer");
        Object minecraftServer = getServerMethod.invoke(craftServer);

        Object serverConnection = findServerConnection(minecraftServer);
        if (serverConnection == null) {
            throw new IllegalStateException("Could not find ServerConnection");
        }

        List<ChannelFuture> futures = findChannelFutureList(serverConnection);
        if (futures == null || futures.isEmpty()) {
            throw new IllegalStateException("Could not find server channel futures");
        }

        for (ChannelFuture future : futures) {
            Channel serverChannel = future.channel();

            if (serverChannel.pipeline().get("evr_server_detector") == null) {
                serverChannel.pipeline().addFirst("evr_server_detector", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        Channel child = extractChannelFromMsg(msg);
                        if (child != null && child.pipeline().get("evr_client_detector") == null) {
                            child.pipeline().addFirst("evr_client_detector", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext clientCtx, Object packet) throws Exception {
                                    tryCaptureHandshake(clientCtx.channel(), packet);
                                    super.channelRead(clientCtx, packet);
                                }
                            });
                        }
                        super.channelRead(ctx, msg);
                    }
                });
            }
        }
    }

    private Object findServerConnection(Object minecraftServer) throws Exception {
        Class<?> type = minecraftServer.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(minecraftServer);
                if (value == null) {
                    continue;
                }

                if (value.getClass().getSimpleName().equals("ServerConnection")) {
                    return value;
                }
            }
            type = type.getSuperclass();
        }

        for (Method method : minecraftServer.getClass().getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.getParameterCount() == 0) {
                Object value;
                try {
                    value = method.invoke(minecraftServer);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value != null && value.getClass().getSimpleName().equals("ServerConnection")) {
                    return value;
                }
            }
        }

        return null;
    }

    private void tryCaptureHandshake(Channel channel, Object packet) {
        try {
            String simpleName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String fullName = packet.getClass().getName().toLowerCase(Locale.ROOT);

            if (!simpleName.contains("handshake") && !fullName.contains("handshake")) {
                return;
            }

            Integer protocol = extractProtocolByFields(packet);
            if (protocol == null) {
                protocol = extractProtocolByMethods(packet);
            }

            if (protocol != null) {
                String key = normalize(channel.remoteAddress());
                addressProtocolMap.put(key, protocol);
                debug("Captured handshake " + key + " protocol=" + protocol + " version=" + resolveVersionName(protocol));
            }
        } catch (Throwable t) {
            debug("Handshake capture failed: " + t.getMessage());
        }
    }

    private Integer extractProtocolByFields(Object packet) {
        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (type == int.class || type == Integer.class) {
                    Object value = field.get(packet);
                    if (value instanceof Integer i && i > 0 && i < 10000) {
                        return i;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer extractProtocolByMethods(Object packet) {
        try {
            for (Method method : packet.getClass().getDeclaredMethods()) {
                method.setAccessible(true);
                if (method.getParameterCount() == 0 && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    Object value = method.invoke(packet);
                    if (value instanceof Integer i && i > 0 && i < 10000) {
                        return i;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Channel extractChannelFromMsg(Object msg) {
        if (msg == null) {
            return null;
        }

        if (msg instanceof Channel c) {
            return c;
        }

        try {
            for (Field field : msg.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(msg);
                if (value instanceof Channel c) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<ChannelFuture> findChannelFutureList(Object serverConnection) throws Exception {
        Class<?> type = serverConnection.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(serverConnection);

                if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof ChannelFuture) {
                    return (List<ChannelFuture>) value;
                }
            }
            type = type.getSuperclass();
        }

        return null;
    }

    private String normalize(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
            return host + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }

    private void debug(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pver")) {
            return false;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("eachversionresources.reload")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }

            reloadLocalSettings();
            loadVersionsRegistry();
            sender.sendMessage("§aEachVersionResources reloaded.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("send")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }

            try {
                sendConfiguredPack(player);
                sender.sendMessage("§aTried to send configured pack.");
            } catch (Throwable t) {
                sender.sendMessage("§cFailed: " + t.getMessage());
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use /pver reload from console.");
            return true;
        }

        Integer protocol = playerProtocolMap.get(player.getUniqueId());
        String version = protocol == null ? "unknown" : resolveVersionName(protocol);

        player.sendMessage("§aProtocol: §f" + (protocol == null ? "unknown" : protocol));
        player.sendMessage("§aVersion: §f" + version);

        ConfigurationSection section = getPackSectionForVersion(version);
        if (section != null && section.getBoolean("enabled", false)) {
            player.sendMessage("§aPack URL: §f" + section.getString("url", ""));
        } else {
            player.sendMessage("§aPack: §fnone configured");
        }

        return true;
    }
}