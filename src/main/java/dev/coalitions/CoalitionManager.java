package dev.coalitions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Менеджер даних і скорбордів:
 *  - зберігає/завантажує coalitions.yml;
 *  - синхронізує коаліції з ванільними Scoreboard Teams (MainScoreboard).
 */
public class CoalitionManager {

    /** Префікс назв наших команд на скорборді, щоб не чіпати чужі команди. */
    private static final String TEAM_PREFIX = "co_";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final CoalitionsPlugin plugin;
    private final File file;

    /** Ключ — назва коаліції в нижньому регістрі. */
    private final Map<String, Coalition> coalitions = new LinkedHashMap<>();
    private volatile Map<UUID, Component> chatTags = Map.of();

    public CoalitionManager(CoalitionsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "coalitions.yml");
    }

    // ------------------------------------------------------------------
    //  Пошук
    // ------------------------------------------------------------------

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Coalition get(String name) {
        return coalitions.get(key(name));
    }

    public Collection<Coalition> getAll() {
        return Collections.unmodifiableCollection(coalitions.values());
    }

    public Coalition findByMember(UUID uuid) {
        for (Coalition c : coalitions.values()) {
            if (c.isMember(uuid)) return c;
        }
        return null;
    }

    public Coalition findByLeader(UUID uuid) {
        for (Coalition c : coalitions.values()) {
            if (uuid.equals(c.getLeader())) return c;
        }
        return null;
    }

    public Component getChatTag(UUID uuid) {
        return chatTags.get(uuid);
    }

    private void refreshChatTags() {
        Map<UUID, Component> updated = new HashMap<>();
        for (Coalition coalition : coalitions.values()) {
            Component tag = LEGACY.deserialize(coalition.getPrefix()).append(Component.space());
            for (UUID memberId : coalition.getMembers().keySet()) {
                updated.put(memberId, tag);
            }
        }
        chatTags = Map.copyOf(updated);
    }

    // ------------------------------------------------------------------
    //  Операції над коаліціями (кожна зберігає дані та оновлює скорборд)
    // ------------------------------------------------------------------

    /**
     * Створює коаліцію.
     *
     * @return коаліцію або null, якщо внутрішнє ім'я команди скорборду конфліктує з іншою коаліцією
     */
    public Coalition createCoalition(String name, String prefix) {
        String newTeamName = teamName(name);
        for (Coalition other : coalitions.values()) {
            if (teamName(other.getName()).equals(newTeamName)) {
                return null; // надзвичайно рідкісна колізія хешу
            }
        }
        Coalition coalition = new Coalition(name, prefix);
        coalitions.put(key(name), coalition);
        save();
        syncCoalition(coalition);
        return coalition;
    }

    public void deleteCoalition(Coalition coalition) {
        coalitions.remove(key(coalition.getName()));
        Team team = board().getTeam(teamName(coalition.getName()));
        if (team != null) team.unregister();
        refreshChatTags();
        save();
    }

    /** Призначає главу; гравець автоматично стає учасником коаліції. */
    public void setLeader(Coalition coalition, UUID uuid, String playerName) {
        coalition.getMembers().put(uuid, playerName);
        coalition.setLeader(uuid);
        save();
        syncCoalition(coalition);
    }

    public void addMember(Coalition coalition, UUID uuid, String playerName) {
        coalition.getMembers().put(uuid, playerName);
        save();
        syncCoalition(coalition);
    }

    public void removeMember(Coalition coalition, UUID uuid) {
        coalition.getMembers().remove(uuid);
        save();
        syncCoalition(coalition); // видалить зайвий запис із команди скорборду
    }

    /** Змінює префікс коаліції, зберігає його та оновлює scoreboard. */
    public void setPrefix(Coalition coalition, String prefix) {
        coalition.setPrefix(prefix);
        save();
        syncCoalition(coalition);
    }

    /** Викликається при вході гравця: оновлює збережений нікнейм і команду на скорборді. */
    public void handleJoin(Player player) {
        Coalition coalition = findByMember(player.getUniqueId());
        if (coalition == null) return;

        String stored = coalition.getMembers().get(player.getUniqueId());
        if (!player.getName().equals(stored)) {
            coalition.getMembers().put(player.getUniqueId(), player.getName()); // гравець змінив нік
            save();
        }
        syncCoalition(coalition);
    }

    // ------------------------------------------------------------------
    //  Scoreboard Teams
    // ------------------------------------------------------------------

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    /**
     * Внутрішнє ім'я команди скорборду. Генерується з хешу назви,
     * щоб гарантовано вкластися в ліміт довжини та уникнути проблем із символами.
     */
    private String teamName(String coalitionName) {
        return TEAM_PREFIX + Integer.toHexString(key(coalitionName).hashCode());
    }

    /** Повна синхронізація: видаляє застарілі команди та оновлює всі існуючі. */
    public void syncAll() {
        Scoreboard sb = board();

        Set<String> valid = new HashSet<>();
        for (Coalition c : coalitions.values()) valid.add(teamName(c.getName()));

        // Видаляємо наші "осиротілі" команди (коаліцію видалили, поки сервер був вимкнений)
        for (Team team : new ArrayList<>(sb.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX) && !valid.contains(team.getName())) {
                team.unregister();
            }
        }
        for (Coalition c : coalitions.values()) syncCoalition(c);
    }

    /** Створює/оновлює команду скорборду для однієї коаліції. */
    public void syncCoalition(Coalition coalition) {
        Scoreboard sb = board();
        String teamName = teamName(coalition.getName());

        Team team = sb.getTeam(teamName);
        if (team == null) team = sb.registerNewTeam(teamName);

        // Префікс з підтримкою кодів &; пробіл відділяє його від ніка.
        // Префікс команди відображається і над головою, і в TAB-листі.
        Component prefix = LEGACY.deserialize(coalition.getPrefix()).append(Component.space());
        team.prefix(prefix);

        // Записи команди = нікнейми учасників
        Set<String> wanted = new HashSet<>(coalition.getMembers().values());
        for (String entry : new ArrayList<>(team.getEntries())) {
            if (!wanted.contains(entry)) team.removeEntry(entry);
        }
        for (String entry : wanted) {
            if (!team.hasEntry(entry)) team.addEntry(entry);
        }
        refreshChatTags();
    }

    // ------------------------------------------------------------------
    //  Збереження / завантаження coalitions.yml
    // ------------------------------------------------------------------

    public void load() {
        coalitions.clear();
        if (!file.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("coalitions");
        if (root == null) return;

        for (String k : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(k);
            if (sec == null) continue;

            Coalition coalition = new Coalition(sec.getString("name", k), sec.getString("prefix", ""));

            String leaderRaw = sec.getString("leader");
            if (leaderRaw != null) {
                try {
                    coalition.setLeader(UUID.fromString(leaderRaw));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Некоректний UUID глави в коаліції " + k);
                }
            }

            ConfigurationSection membersSec = sec.getConfigurationSection("members");
            if (membersSec != null) {
                for (String uuidRaw : membersSec.getKeys(false)) {
                    try {
                        coalition.getMembers().put(UUID.fromString(uuidRaw), membersSec.getString(uuidRaw, uuidRaw));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Некоректний UUID учасника в коаліції " + k + ": " + uuidRaw);
                    }
                }
            }
            coalitions.put(key(coalition.getName()), coalition);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Coalition c : coalitions.values()) {
            String base = "coalitions." + key(c.getName());
            yml.set(base + ".name", c.getName());
            yml.set(base + ".prefix", c.getPrefix());
            yml.set(base + ".leader", c.getLeader() == null ? null : c.getLeader().toString());
            for (Map.Entry<UUID, String> e : c.getMembers().entrySet()) {
                yml.set(base + ".members." + e.getKey(), e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Не вдалося зберегти coalitions.yml: " + ex.getMessage());
        }
    }
}
