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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
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

        Bukkit.getScheduler().runTask(this, () -> {
            try {
                injectServerChannels();
                log("Netty handshake detector injected successfully");
            } catch (Throwable t) {
                getLogger().severe("Netty injection failed: " + t.getMessage());
            }
        });

        long refreshTicks = Math.max(20L * 60L, 20L * 60L * 60L * refreshHours);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                loadVersionsRegistry();
            } catch (Throwable ignored) {}
        }, refreshTicks, refreshTicks);

        log("Plugin enabled with " + protocolNameMap.size() + " protocol mappings");
    }

    @Override
    public void onDisable() {
        log("Plugin disabled");
    }

    private void reloadLocalSettings() {
        reloadConfig();
        remoteVersionsUrl = getConfig().getString(
                "versions-url",
                "https://raw.githubusercontent.com/Bluezly/iconforgrowstock/refs/heads/main/versions.json"
        );
        refreshHours = getConfig().getLong("refresh-hours", 6);
        debug = getConfig().getBoolean("debug", true);
        fallbackEnabled = getConfig().getBoolean("fallback.enabled", true);
        sendOnJoin = getConfig().getBoolean("send-pack-on-join", true);
        useDefaultPack = getConfig().getBoolean("default-pack.enabled", false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        String key = normalizeAddress(player.getAddress());
        log("[Join] key=" + key + " | addressMap=" + addressProtocolMap.keySet());

        Integer protocol = addressProtocolMap.remove(key);

        if (protocol == null) {
            log("[Join] No protocol in map for key: " + key);
        } else {
            playerProtocolMap.put(player.getUniqueId(), protocol);
            log("[Join] " + player.getName() + " -> protocol=" + protocol + " version=" + resolveVersionName(protocol));
        }

        if (sendOnJoin) {
            Bukkit.getScheduler().runTaskLater(this, () -> sendConfiguredPack(player), 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerProtocolMap.remove(event.getPlayer().getUniqueId());
    }

    private void sendConfiguredPack(Player player) {
        Integer protocol = playerProtocolMap.get(player.getUniqueId());
        if (protocol == null) {
            log("[Pack] No protocol for " + player.getName());
            return;
        }

        String versionName = resolveVersionName(protocol);
        log("[Pack] " + player.getName() + " | version=" + versionName + " protocol=" + protocol);

        ConfigurationSection section = getPackSectionForVersion(versionName);
        if (section == null) {
            log("[Pack] No section found for: " + versionName);
            return;
        }

        if (!section.getBoolean("enabled", false)) {
            log("[Pack] Disabled for: " + versionName);
            return;
        }

        String url = section.getString("url", "");
        String sha1 = section.getString("sha1", "");
        boolean required = section.getBoolean("required", false);

        if (url.isBlank()) {
            log("[Pack] URL empty for: " + versionName);
            return;
        }

        try {
            try {
                Method modern = player.getClass().getMethod("setResourcePack", String.class, String.class, boolean.class);
                modern.invoke(player, url, sha1, required);
                log("[Pack] Sent (modern) to " + player.getName());
                return;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method older = player.getClass().getMethod("setResourcePack", String.class, byte[].class);
                older.invoke(player, url, parseSha1Bytes(sha1));
                log("[Pack] Sent (older) to " + player.getName());
                return;
            } catch (NoSuchMethodException ignored) {}

            player.setResourcePack(url);
            log("[Pack] Sent (legacy) to " + player.getName());
        } catch (Throwable t) {
            getLogger().warning("[Pack] Failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    private ConfigurationSection getPackSectionForVersion(String versionName) {
        ConfigurationSection packs = getConfig().getConfigurationSection("packs");
        if (packs != null) {
            ConfigurationSection section = packs.getConfigurationSection(versionName);
            if (section != null) return section;
        }
        if (useDefaultPack) {
            return getConfig().getConfigurationSection("default-pack");
        }
        return null;
    }

    private byte[] parseSha1Bytes(String sha1) {
        if (sha1 == null || sha1.length() != 40) return new byte[0];
        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            int index = i * 2;
            result[i] = (byte) Integer.parseInt(sha1.substring(index, index + 2), 16);
        }
        return result;
    }

    private void loadVersionsRegistry() {
        try {
            String json = downloadText(remoteVersionsUrl);
            if (json != null && !json.isBlank()) {
                parseProtocolMap(json);
                Files.writeString(cacheFile.toPath(), json, StandardCharsets.UTF_8);
                return;
            }
        } catch (Throwable ignored) {}

        try {
            if (cacheFile.exists()) {
                String json = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
                parseProtocolMap(json);
                return;
            }
        } catch (Throwable ignored) {}

        if (fallbackEnabled) {
            loadMinimalFallback();
        }
    }

    private void parseProtocolMap(String json) {
        protocolNameMap.clear();
        Pattern blockPattern = Pattern.compile("\"by_protocol\"\\s*:\\s*\\{([\\s\\S]*?)\\}");
        Matcher blockMatcher = blockPattern.matcher(json);
        if (!blockMatcher.find()) return;
        String body = blockMatcher.group(1);
        Pattern pairPattern = Pattern.compile("\"(\\d+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pairMatcher = pairPattern.matcher(body);
        while (pairMatcher.find()) {
            int protocol = Integer.parseInt(pairMatcher.group(1));
            String version = pairMatcher.group(2);
            protocolNameMap.put(protocol, version);
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
    }

    private String resolveVersionName(int protocol) {
        return protocolNameMap.getOrDefault(protocol, "unknown(" + protocol + ")");
    }

    private String downloadText(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "EachVersionResources");
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void injectServerChannels() throws Exception {
        Object craftServer = Bukkit.getServer();
        Method getServerMethod = craftServer.getClass().getMethod("getServer");
        Object minecraftServer = getServerMethod.invoke(craftServer);

        log("[Inject] MinecraftServer: " + minecraftServer.getClass().getName());

        Object serverConnection = findServerConnection(minecraftServer);
        if (serverConnection == null) throw new IllegalStateException("ServerConnection not found");

        log("[Inject] ServerConnection: " + serverConnection.getClass().getName());

        List<ChannelFuture> futures = findChannelFutureList(serverConnection);
        if (futures == null || futures.isEmpty()) throw new IllegalStateException("ChannelFuture list not found");

        for (ChannelFuture future : futures) {
            Channel serverChannel = future.channel();
            if (serverChannel.pipeline().get("evr_server_detector") == null) {
                serverChannel.pipeline().addFirst("evr_server_detector", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        Channel child = extractChannel(msg);
                        if (child != null && child.pipeline().get("evr_client_detector") == null) {
                            child.pipeline().addFirst("evr_client_detector", new ChannelInboundHandlerAdapter() {
                                private boolean captured = false;
                                private int packetCount = 0;

                                @Override
                                public void channelRead(ChannelHandlerContext clientCtx, Object packet) throws Exception {
                                    if (!captured && packetCount < 5) {
                                        packetCount++;
                                        logPacketDebug(clientCtx.channel(), packet, packetCount);
                                        captured = tryCaptureHandshake(clientCtx.channel(), packet);
                                    }
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

    private void logPacketDebug(Channel channel, Object packet, int count) {
        if (!debug) return;
        String simpleName = packet.getClass().getSimpleName();
        String fullName = packet.getClass().getName();
        log("[Packet #" + count + "] simple=" + simpleName + " | full=" + fullName);

        try {
            StringBuilder fields = new StringBuilder();
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(packet);
                fields.append(field.getName()).append("=").append(value).append(" ");
            }
            log("[Packet #" + count + "] fields: " + fields);
        } catch (Throwable ignored) {}
    }

    private Object findServerConnection(Object minecraftServer) throws Exception {
        Class<?> type = minecraftServer.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(minecraftServer);
                if (value == null) continue;
                String name = value.getClass().getSimpleName();
                if (name.equals("ServerConnection")
                        || name.equals("ServerConnectionListener")
                        || name.contains("ServerConnection")
                        || name.contains("ConnectionListener")) {
                    return value;
                }
            }
            type = type.getSuperclass();
        }
        for (Method method : minecraftServer.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 0) continue;
            String returnName = method.getReturnType().getSimpleName();
            if (returnName.equals("ServerConnection")
                    || returnName.equals("ServerConnectionListener")
                    || returnName.contains("ServerConnection")
                    || returnName.contains("ConnectionListener")) {
                method.setAccessible(true);
                Object value = method.invoke(minecraftServer);
                if (value != null) return value;
            }
        }
        return null;
    }

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

    private boolean tryCaptureHandshake(Channel channel, Object packet) {
        try {
            String simpleName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String fullName = packet.getClass().getName().toLowerCase(Locale.ROOT);

            boolean isHandshake = simpleName.contains("intention")
                    || fullName.contains("intention")
                    || simpleName.contains("handshake")
                    || fullName.contains("handshake")
                    || simpleName.contains("c2s")
                    || fullName.contains("c2s")
                    || simpleName.contains("login")
                    || fullName.contains("packethandshaking")
                    || simpleName.contains("clientintention")
                    || fullName.contains("clientintention");

            if (!isHandshake) return false;

            log("[Capture] Matched packet: " + packet.getClass().getName());

            Integer protocol = extractProtocolSmart(packet);
            if (protocol != null) {
                String key = normalizeChannel(channel.remoteAddress());
                addressProtocolMap.put(key, protocol);
                log("[Capture] protocol=" + protocol + " key=" + key);
                return true;
            }
            log("[Capture] Could not extract protocol from: " + packet.getClass().getName());
        } catch (Throwable t) {
            log("[Capture] Failed: " + t.getMessage());
        }
        return false;
    }

    private Integer extractProtocolSmart(Object packet) {
        List<Integer> candidates = new ArrayList<>();

        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == int.class || field.getType() == Integer.class) {
                    Object value = field.get(packet);
                    if (value instanceof Integer i && i > 0 && i < 10000) {
                        String name = field.getName().toLowerCase(Locale.ROOT);
                        log("[Extract] int field: " + field.getName() + "=" + i);
                        if (name.contains("protocol") || name.contains("version")) return i;
                        candidates.add(i);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!candidates.isEmpty()) {
            log("[Extract] Using first candidate: " + candidates.get(0));
            return candidates.get(0);
        }

        try {
            for (Method method : packet.getClass().getDeclaredMethods()) {
                method.setAccessible(true);
                if (method.getParameterCount() == 0
                        && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("protocol") || name.contains("version")) {
                        Object value = method.invoke(packet);
                        if (value instanceof Integer i && i > 0 && i < 10000) {
                            log("[Extract] method " + method.getName() + "=" + i);
                            return i;
                        }
                    }
                }
            }
            for (Method method : packet.getClass().getDeclaredMethods()) {
                method.setAccessible(true);
                if (method.getParameterCount() == 0
                        && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    Object value = method.invoke(packet);
                    if (value instanceof Integer i && i > 0 && i < 10000) {
                        log("[Extract] fallback method " + method.getName() + "=" + i);
                        return i;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private Channel extractChannel(Object msg) {
        if (msg instanceof Channel c) return c;
        try {
            for (Field field : msg.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(msg);
                if (value instanceof Channel c) return c;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String normalizeChannel(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null
                    ? inet.getAddress().getHostAddress()
                    : inet.getHostString();
            return host + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }

    private String normalizeAddress(InetSocketAddress address) {
        if (address == null) return "null";
        String host = address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : address.getHostString();
        return host + ":" + address.getPort();
    }

    private void log(String message) {
        if (debug) getLogger().info(message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pver")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadLocalSettings();
            loadVersionsRegistry();
            sender.sendMessage("EachVersionResources reloaded. Mappings: " + protocolNameMap.size());
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            sender.sendMessage("addressProtocolMap size: " + addressProtocolMap.size());
            sender.sendMessage("playerProtocolMap size: " + playerProtocolMap.size());
            sender.sendMessage("protocolNameMap size: " + protocolNameMap.size());
            return true;
        }

        if (sender instanceof Player player) {
            Integer protocol = playerProtocolMap.get(player.getUniqueId());
            if (protocol == null) {
                sender.sendMessage("Protocol: not detected");
                sender.sendMessage("Version: not detected");
                return true;
            }
            sender.sendMessage("Protocol: " + protocol);
            sender.sendMessage("Version: " + resolveVersionName(protocol));
            return true;
        }

        sender.sendMessage("Usage: /pver | /pver reload | /pver debug");
        return true;
    }
}
