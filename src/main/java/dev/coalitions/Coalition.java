package dev.coalitions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Модель коаліції: назва, префікс, глава та учасники.
 */
public class Coalition {

    private final String name;
    private String prefix;
    private UUID leader; // може бути null, поки адмін не призначив главу

    /** UUID учасника -> його останній відомий нікнейм (глава теж є учасником). */
    private final Map<UUID, String> members = new LinkedHashMap<>();

    public Coalition(String name, String prefix) {
        this.name = name;
        this.prefix = prefix;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public UUID getLeader() {
        return leader;
    }

    public void setLeader(UUID leader) {
        this.leader = leader;
    }

    public Map<UUID, String> getMembers() {
        return members;
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }
}
