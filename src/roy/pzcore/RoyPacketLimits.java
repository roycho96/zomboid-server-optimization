package roy.pzcore;

import java.util.Map;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerOptions;

public final class RoyPacketLimits {
    private RoyPacketLimits() {}

    public static boolean exceeded(PacketRateWindows<PacketTypes.PacketType> windows, PacketTypes.PacketType type) {
        long now = System.currentTimeMillis();
        boolean exceeded = windows.check(type, now, ServerOptions.getInstance().maxPacketsPerSecond.getValue(), GameClient.client, GameServer.server);
        if (exceeded) {
            DebugType.Multiplayer.warn("Packets limit has exceeded for %s", new Object[]{type.name()});
            if (Core.debug) {
                StringBuilder message = new StringBuilder();
                for (Map.Entry<PacketTypes.PacketType, Integer> entry : windows.counts(now).entrySet()) {
                    if (entry.getValue() != 0) message.append(entry.getKey()).append("=").append(entry.getValue()).append(", ");
                }
                DebugType.Multiplayer.debugln(message.toString());
            }
        }
        return exceeded;
    }
}
