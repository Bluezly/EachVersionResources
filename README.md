# EachVersionResources

Minecraft Paper plugin that sends **different resource packs depending on the player's Minecraft version**.

The plugin detects the **client protocol directly from the handshake packet** (no ViaVersion or ProtocolLib required) and then converts it to a Minecraft version using a **remote `versions.json` API**.

This means the plugin **automatically supports new Minecraft versions** without needing to update the plugin.

---

# Features

- Detects **player protocol directly from network handshake**
- Works **without ProtocolLib**
- Works **without ViaVersion**
- Uses **remote versions API** for automatic updates
- Supports **different resource packs per version**
- Optional **default resource pack**
- **Cache system** if API fails
- **Config reload command**
- Fully configurable

---

# How It Works

1. Player connects to the server.
2. Plugin reads the **protocol version from the handshake packet**.
3. Plugin downloads `versions.json`:
   
