package dev.coalitions;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Обробник команди /co та її автодоповнення.
 */
public class CoalitionCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PERM_ADMIN = "coalition.admin";

    /** Назва коаліції: літери (будь-які мови), цифри, _ та -, довжина 2-24. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{2,24}");
    private static final int MAX_PREFIX_LENGTH = 48;
    private final CoalitionManager manager;
    private final Messages messages;
    private final Map<String, String> colors;

    public CoalitionCommand(CoalitionManager manager) {
        this.manager = manager;
        this.messages = new Messages();
        this.colors = Map.ofEntries(
                Map.entry(messages.get("color.black"), "0"),
                Map.entry(messages.get("color.dark_blue"), "1"),
                Map.entry(messages.get("color.dark_green"), "2"),
                Map.entry(messages.get("color.dark_aqua"), "3"),
                Map.entry(messages.get("color.dark_red"), "4"),
                Map.entry(messages.get("color.dark_purple"), "5"),
                Map.entry(messages.get("color.gold"), "6"),
                Map.entry(messages.get("color.gray"), "7"),
                Map.entry(messages.get("color.dark_gray"), "8"),
                Map.entry(messages.get("color.blue"), "9"),
                Map.entry(messages.get("color.green"), "a"),
                Map.entry(messages.get("color.aqua"), "b"),
                Map.entry(messages.get("color.red"), "c"),
                Map.entry(messages.get("color.light_purple"), "d"),
                Map.entry(messages.get("color.yellow"), "e"),
                Map.entry(messages.get("color.white"), "f"));
    }

    // ------------------------------------------------------------------
    //  Виконання команд
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> handleCreate(sender, args);
            case "setleader" -> handleSetLeader(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "add" -> handleAdd(sender, args);
            case "kick" -> handleKick(sender, args);
            case "color" -> handleColor(sender, args);
            case "prefix" -> handlePrefix(sender, args);
            case "info" -> handleInfo(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ---------------------------- Адмін ----------------------------

    /** /co create <назва> <префікс> */
    private void handleCreate(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 3) {
            send(sender, "command.create.usage");
            return;
        }

        String name = args[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            send(sender, "command.create.invalid_name");
            return;
        }
        // Префікс може містити пробіли — склеюємо решту аргументів
        String prefix = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            send(sender, "command.prefix.too_long_with_codes", MAX_PREFIX_LENGTH);
            return;
        }
        if (manager.get(name) != null) {
            send(sender, "command.create.exists", name);
            return;
        }

        Coalition coalition = manager.createCoalition(name, prefix);
        if (coalition == null) {
            send(sender, "command.create.conflict");
            return;
        }
        send(sender, "command.create.success", name, prefix);
        send(sender, "command.create.leader_hint", name);
    }

    /** /co setleader <назва_коаліції> <нік_гравця> */
    private void handleSetLeader(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 3) {
            send(sender, "command.setleader.usage");
            return;
        }

        Coalition coalition = manager.get(args[1]);
        if (coalition == null) {
            send(sender, "command.coalition.not_found", args[1]);
            return;
        }
        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) {
            send(sender, "command.player.not_found", args[2]);
            return;
        }

        Coalition existing = manager.findByMember(target.getUniqueId());
        if (existing != null && existing != coalition) {
            send(sender, "command.player.already_in_coalition", existing.getName());
            return;
        }
        if (target.getUniqueId().equals(coalition.getLeader())) {
            send(sender, "command.player.already_leader");
            return;
        }

        String targetName = displayName(target, args[2]);
        manager.setLeader(coalition, target.getUniqueId(), targetName);

        send(sender, "command.setleader.success", targetName, coalition.getName());
        notifyPlayer(target, "command.setleader.notification", coalition.getName());
    }

    /** /co delete <назва> — додаткова команда для адміністраторів. */
    private void handleDelete(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) {
            send(sender, "command.delete.usage");
            return;
        }
        Coalition coalition = manager.get(args[1]);
        if (coalition == null) {
            send(sender, "command.coalition.not_found", args[1]);
            return;
        }
        manager.deleteCoalition(coalition);
        send(sender, "command.delete.success", coalition.getName());
    }

    // ---------------------------- Глава ----------------------------

    /** /co add <нік_гравця> */
    private void handleAdd(CommandSender sender, String[] args) {
        Coalition coalition = requireLeader(sender);
        if (coalition == null) return;
        if (args.length < 2) {
            send(sender, "command.add.usage");
            return;
        }

        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            send(sender, "command.player.not_found", args[1]);
            return;
        }
        Coalition existing = manager.findByMember(target.getUniqueId());
        if (existing != null) {
            send(sender, "command.player.already_in_coalition", existing.getName());
            return;
        }

        String targetName = displayName(target, args[1]);
        manager.addMember(coalition, target.getUniqueId(), targetName);

        send(sender, "command.add.success", targetName);
        notifyPlayer(target, "command.add.notification", coalition.getName());
    }

    /** /co kick <нік_гравця> */
    private void handleKick(CommandSender sender, String[] args) {
        Coalition coalition = requireLeader(sender);
        if (coalition == null) return;
        if (args.length < 2) {
            send(sender, "command.kick.usage");
            return;
        }

        // Шукаємо серед учасників за збереженим нікнеймом (працює і для офлайн-гравців)
        UUID targetId = null;
        String targetName = null;
        for (Map.Entry<UUID, String> e : coalition.getMembers().entrySet()) {
            if (e.getValue().equalsIgnoreCase(args[1])) {
                targetId = e.getKey();
                targetName = e.getValue();
                break;
            }
        }
        if (targetId == null) {
            send(sender, "command.kick.not_member", args[1]);
            return;
        }
        if (targetId.equals(coalition.getLeader())) {
            send(sender, "command.kick.leader_denied");
            return;
        }

        manager.removeMember(coalition, targetId);
        send(sender, "command.kick.success", targetName);

        Player online = Bukkit.getPlayer(targetId);
        if (online != null) {
            send(online, "command.kick.notification", coalition.getName());
        }
    }

    /** /co color <назва_кольору|код> — глава змінює колір свого префікса. */
    private void handleColor(CommandSender sender, String[] args) {
        Coalition coalition = requireLeader(sender);
        if (coalition == null) return;
        if (args.length != 2) {
            send(sender, "command.color.usage");
            return;
        }

        String colorCode = colorCode(args[1]);
        if (colorCode == null) {
            send(sender, "command.color.invalid");
            return;
        }

        String text = coalition.getPrefix().replaceAll("(?i)&[0-9a-f]", "");
        manager.setPrefix(coalition, "&" + colorCode + text);
        send(sender, "command.color.success", coalition.getName());
    }

    /** /co prefix <новый_префикс> — глава устанавливает текст и цвет префикса. */
    private void handlePrefix(CommandSender sender, String[] args) {
        Coalition coalition = requireLeader(sender);
        if (coalition == null) return;
        if (args.length < 2) {
            send(sender, "command.prefix.usage");
            return;
        }

        String prefix = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            send(sender, "command.prefix.too_long", MAX_PREFIX_LENGTH);
            return;
        }
        manager.setPrefix(coalition, prefix);
        send(sender, "command.prefix.success", prefix);
    }

    private String colorCode(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("&") && normalized.length() == 2) normalized = normalized.substring(1);
        if (normalized.length() == 1 && "0123456789abcdef".contains(normalized)) return normalized;
        return colors.get(normalized);
    }

    // ---------------------------- Інфо ----------------------------

    /** /co info [назва] — інформація про свою або вказану коаліцію. */
    private void handleInfo(CommandSender sender, String[] args) {
        Coalition coalition;
        if (args.length >= 2) {
            coalition = manager.get(args[1]);
        } else if (sender instanceof Player player) {
            coalition = manager.findByMember(player.getUniqueId());
        } else {
            send(sender, "command.info.usage");
            return;
        }
        if (coalition == null) {
            send(sender, "command.info.not_found");
            return;
        }

        String leaderName = coalition.getLeader() == null
                ? messages.get("command.info.leader_unassigned")
                : "&e" + coalition.getMembers().getOrDefault(coalition.getLeader(), "?");
        List<String> names = new ArrayList<>(coalition.getMembers().values());

        send(sender, "command.info.title", coalition.getName(), coalition.getPrefix());
        send(sender, "command.info.leader", leaderName);
        send(sender, "command.info.members", names.size(), String.join("&7, &f", names));
    }

    // ------------------------------------------------------------------
    //  Допоміжні методи
    // ------------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        send(sender, "help.title");
        send(sender, "help.info");
        if (sender instanceof Player player && manager.findByLeader(player.getUniqueId()) != null) {
            send(sender, "help.add");
            send(sender, "help.kick");
            send(sender, "help.color");
            send(sender, "help.prefix");
        }
        if (sender.hasPermission(PERM_ADMIN)) {
            send(sender, "help.create");
            send(sender, "help.setleader");
            send(sender, "help.delete");
        }
    }

    private void send(CommandSender target, String key, Object... arguments) {
        target.sendMessage(LEGACY.deserialize(
                messages.get("chat.prefix") + messages.get(key, arguments)));
    }

    private void notifyPlayer(OfflinePlayer target, String key, Object... arguments) {
        Player online = target.getPlayer();
        if (online != null) send(online, key, arguments);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(PERM_ADMIN)) return true;
        send(sender, "error.no_admin_permission");
        return false;
    }

    /** Повертає коаліцію, главою якої є відправник, або null (з повідомленням про помилку). */
    private Coalition requireLeader(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player_only");
            return null;
        }
        Coalition coalition = manager.findByLeader(player.getUniqueId());
        if (coalition == null) {
            send(sender, "error.leader_only");
        }
        return coalition;
    }

    /** Гравець онлайн або той, хто вже заходив на сервер (є в кеші імен). */
    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private String displayName(OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    // ------------------------------------------------------------------
    //  TabCompleter
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission(PERM_ADMIN);
        Coalition led = (sender instanceof Player p) ? manager.findByLeader(p.getUniqueId()) : null;

        // /co <підкоманда>
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("info"));
            if (admin) subs.addAll(List.of("create", "setleader", "delete"));
            if (led != null) subs.addAll(List.of("add", "kick", "color", "prefix"));
            return filter(subs, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create":
                if (admin && args.length == 3) return filter(List.of(messages.get("prefix.suggestion")), args[2]);
                break;
            case "setleader":
                if (!admin) break;
                if (args.length == 2) return filter(coalitionNames(), args[1]);
                if (args.length == 3) return filter(onlinePlayerNames(), args[2]);
                break;
            case "delete":
                if (admin && args.length == 2) return filter(coalitionNames(), args[1]);
                break;
            case "info":
                if (args.length == 2) return filter(coalitionNames(), args[1]);
                break;
            case "color":
                if (led != null && args.length == 2) {
                    List<String> suggestions = new ArrayList<>(colors.keySet());
                    suggestions.addAll(List.of("&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7",
                            "&8", "&9", "&a", "&b", "&c", "&d", "&e", "&f"));
                    return filter(suggestions, args[1]);
                }
                break;
            case "prefix":
                if (led != null && args.length == 2) return filter(List.of(messages.get("prefix.suggestion")), args[1]);
                break;
            case "add":
                if (led != null && args.length == 2) {
                    // Пропонуємо лише гравців без коаліції
                    List<String> free = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (manager.findByMember(online.getUniqueId()) == null) free.add(online.getName());
                    }
                    return filter(free, args[1]);
                }
                break;
            case "kick":
                if (led != null && args.length == 2) {
                    List<String> members = new ArrayList<>();
                    for (Map.Entry<UUID, String> e : led.getMembers().entrySet()) {
                        if (!e.getKey().equals(led.getLeader())) members.add(e.getValue());
                    }
                    return filter(members, args[1]);
                }
                break;
            default:
                break;
        }
        return Collections.emptyList();
    }

    private List<String> coalitionNames() {
        List<String> names = new ArrayList<>();
        for (Coalition c : manager.getAll()) names.add(c.getName());
        return names;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }
}
