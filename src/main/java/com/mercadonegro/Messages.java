package com.mercadonegro;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final MercadoNegroPlugin plugin;
    private YamlConfiguration yaml = new YamlConfiguration();

    public Messages(MercadoNegroPlugin plugin) { this.plugin = plugin; }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yaml = YamlConfiguration.loadConfiguration(f);
    }

    public boolean has(String key) { return yaml.contains(key); }

    public String raw(String key, String... kv) {
        return replace(yaml.getString(key, "&cMensaje faltante: " + key), kv);
    }

    public Component component(String key, String... kv) {
        return build(raw(key, kv));
    }

    public Component prefixed(String key, String... kv) {
        return build(yaml.getString("prefix", "") + raw(key, kv));
    }

    public List<Component> lines(String key, String... kv) {
        List<Component> out = new ArrayList<>();
        for (String s : yaml.getStringList(key)) out.add(build(replace(s, kv)));
        return out;
    }

    private static Component build(String s) {
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(LEGACY.deserialize(s));
    }

    private static String replace(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }
}
