import java.nio.file.Path;

public final class VerifyServerPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected generated jar and game jar");
        Path generated = Path.of(args[0]).toRealPath(), game = Path.of(args[1]).toRealPath();
        for (String name : new String[]{"zombie.iso.IsoCell", "zombie.network.PacketsCache", "roy.pzcore.RoyPacketLimits", "roy.pzcore.PacketRateWindows", "roy.pzcore.ServerZombieMembership", "zombie.popman.NetworkZombiePacker", "zombie.iso.Vector2", "zombie.network.ServerLOS", "roy.pzcore.ZombieRequestIndex", "roy.pzcore.ServerDirectionMemo"}) {
            verify(name, generated);
        }
        verify("zombie.network.GameServer", game);
        verify("zombie.vehicles.VehicleInterpolation", game);
        for (String name : new String[]{"roy.pzcore.PacketInbox", "zombie.vehicles.RoyVehicleNet", "roy.pznet.MotionMath"}) {
            try {
                Class.forName(name, false, VerifyServerPatch.class.getClassLoader());
                throw new IllegalStateException("Excluded helper was found: " + name);
            } catch (ClassNotFoundException expected) {
            }
        }
        Class<?> settings = Class.forName("roy.pzcore.CoreSettings", true, VerifyServerPatch.class.getClassLoader());
        if (settings.getField("PACKET_BATCHING").getBoolean(null)) throw new IllegalStateException("Experimental batching is enabled");
        boolean membership = settings.getField("SERVER_ZOMBIE_MEMBERSHIP_HINTS").getBoolean(null);
        boolean requests = settings.getField("SERVER_ZOMBIE_REQUEST_INDEX").getBoolean(null);
        boolean direction = settings.getField("SERVER_DIRECTION_MEMO").getBoolean(null);
        boolean vocals = settings.getField("SERVER_ZOMBIE_VOCALS_GUARD").getBoolean(null);
        System.out.println("{\"verified\":true,\"game_server_original\":true,\"vehicle_patch_absent\":true,\"packet_batching\":false,\"server_zombie_vocals_guard\":" + vocals + ",\"server_zombie_membership_hints\":" + membership + ",\"server_zombie_request_index\":" + requests + ",\"server_direction_memo\":" + direction + "}");
    }

    private static void verify(String name, Path expected) throws Exception {
        Class<?> type = Class.forName(name, false, VerifyServerPatch.class.getClassLoader());
        type.getDeclaredMethods();
        type.getDeclaredFields();
        Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!origin.equals(expected)) throw new IllegalStateException("Unexpected class origin for " + name + ": " + origin);
    }
}
