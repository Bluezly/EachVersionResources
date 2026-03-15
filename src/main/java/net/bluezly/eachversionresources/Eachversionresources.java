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
                log("Handshake detector injected");
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
        log("Player join address key: " + key);
        log("Current addressProtocolMap keys: " + addressProtocolMap.keySet());

        Integer protocol = addressProtocolMap.remove(key);

        if (protocol != null) {
            playerProtocolMap.put(player.getUniqueId(), protocol);
            log("Mapped protocol " + protocol + " to player " + player.getName());
        } else {
            log("No protocol found for key: " + key + " (map size: " + addressProtocolMap.size() + ")");
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
        if (protocol == null) return;

        String versionName = resolveVersionName(protocol);

        ConfigurationSection section = getPackSectionForVersion(versionName);
        if (section == null) return;

        if (!section.getBoolean("enabled", false)) return;

        String url = section.getString("url", "");
        String sha1 = section.getString("sha1", "");
        boolean required = section.getBoolean("required", false);

        if (url.isBlank()) return;

        try {
            try {
                Method modern = player.getClass().getMethod("setResourcePack", String.class, String.class, boolean.class);
                modern.invoke(player, url, sha1, required);
                return;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method older = player.getClass().getMethod("setResourcePack", String.class, byte[].class);
                older.invoke(player, url, parseSha1Bytes(sha1));
                return;
            } catch (NoSuchMethodException ignored) {}

            player.setResourcePack(url);
        } catch (Throwable ignored) {}
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

        log("MinecraftServer class: " + minecraftServer.getClass().getName());

        Object serverConnection = findServerConnection(minecraftServer);
        if (serverConnection == null) {
            throw new IllegalStateException("ServerConnection not found in " + minecraftServer.getClass().getName());
        }

        log("Server connection class: " + serverConnection.getClass().getName());

        List<ChannelFuture> futures = findChannelFutureList(serverConnection);
        if (futures == null || futures.isEmpty()) {
            throw new IllegalStateException("ChannelFuture list not found in " + serverConnection.getClass().getName());
        }

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

                                @Override
                                public void channelRead(ChannelHandlerContext clientCtx, Object packet) throws Exception {
                                    if (!captured) {
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
                    || simpleName.contains("c2shandshake")
                    || fullName.contains("packethandshakingc2s");

            if (!isHandshake) return false;

            log("Detected handshake packet class: " + packet.getClass().getName());

            Integer protocol = extractProtocolSmart(packet);

            if (protocol != null) {
                String key = normalizeChannel(channel.remoteAddress());
                addressProtocolMap.put(key, protocol);
                log("Captured protocol " + protocol + " -> key " + key);
                return true;
            } else {
                log("Could not extract protocol from " + packet.getClass().getName());
            }
        } catch (Throwable t) {
            log("Handshake capture failed: " + t.getMessage());
        }

        return false;
    }

    private Integer extractProtocolSmart(Object packet) {
        List<Integer> candidates = new ArrayList<>();

        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Class<?> type = field.getType();

                if (type == int.class || type == Integer.class) {
                    Object value = field.get(packet);
                    if (value instanceof Integer i && i > 0 && i < 10000) {
                        String name = field.getName().toLowerCase(Locale.ROOT);
                        if (name.contains("protocol") || name.contains("version")) {
                            return i;
                        }
                        candidates.add(i);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!candidates.isEmpty()) {
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
            sender.sendMessage("EachVersionResources reloaded. Loaded " + protocolNameMap.size() + " mappings.");
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
