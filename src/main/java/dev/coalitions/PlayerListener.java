package dev.coalitions;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Слухач подій гравців.
 */
public class PlayerListener implements Listener {

    private final CoalitionManager manager;

    public PlayerListener(CoalitionManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Component tag = manager.getChatTag(event.getPlayer().getUniqueId());
        if (tag == null) return;

        event.renderer((source, sourceDisplayName, message, viewer) ->
                tag.append(sourceDisplayName)
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(message));
    }
}
