package dev.coalitions;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Головний клас плагіна "Coalitions".
 */
public final class CoalitionsPlugin extends JavaPlugin {

    private CoalitionManager manager;

    @Override
    public void onEnable() {
        // 1. Завантажуємо дані з coalitions.yml
        manager = new CoalitionManager(this);
        manager.load();

        // 2. Синхронізуємо ванільні команди (Teams) з завантаженими даними
        manager.syncAll();

        // 3. Реєструємо команду та TabCompleter
        PluginCommand command = getCommand("co");
        if (command == null) {
            getLogger().severe("Команду /co не знайдено у plugin.yml! Плагін вимикається.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CoalitionCommand handler = new CoalitionCommand(manager);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        // 4. Слухач входу гравців (оновлення нікнейму / повторне додавання в команду)
        getServer().getPluginManager().registerEvents(new PlayerListener(manager), this);

        getLogger().info("Завантажено коаліцій: " + manager.getAll().size());
    }

    @Override
    public void onDisable() {
        // Додатково зберігаємо дані при вимкненні сервера.
        // Scoreboard Teams самі зберігаються ванільним scoreboard.dat,
        // а при наступному запуску syncAll() приводить їх до актуального стану.
        if (manager != null) {
            manager.save();
        }
    }
}
