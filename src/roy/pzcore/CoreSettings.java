package roy.pzcore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CoreSettings {
    private static final Properties SETTINGS = load();
    public static final boolean PACKET_BATCHING = false;
    public static final boolean EMPTY_REMOVAL_GUARD = enabled("emptyRemovalGuard", true);
    public static final boolean PACKET_RATE_WINDOWS = enabled("packetRateWindows", true);
    public static final boolean SERVER_ZOMBIE_VOCALS_GUARD = enabled("serverZombieVocalsGuard", true);

    public static final boolean SERVER_ZOMBIE_MEMBERSHIP_HINTS = enabled("serverZombieMembershipHints", true);

    public static final boolean SERVER_ZOMBIE_REQUEST_INDEX = enabled("serverZombieRequestIndex", true);
    public static final boolean SERVER_DIRECTION_MEMO = enabled("serverDirectionMemo", true);

    private CoreSettings() {}

    private static boolean enabled(String name, boolean fallback) {
        return Boolean.parseBoolean(System.getProperty("roy.core." + name,
            SETTINGS.getProperty(name, Boolean.toString(fallback))));
    }

    private static Properties load() {
        Properties values = new Properties();
        try {
            Path artifact = Path.of(CoreSettings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path file = artifact.getParent().resolve("roy-core.properties");
            if (Files.isRegularFile(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            }
        } catch (Exception ignored) {
            // Disabled experimental queue batching is independent of this file.
        }
        return values;
    }
}
