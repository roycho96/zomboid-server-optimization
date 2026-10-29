package roy.pzcore;

import java.util.ArrayList;
import zombie.characters.IsoZombie;
import zombie.network.GameServer;

public final class ServerZombieMembership {
    private static final int[] positions = new int[1 << 16];

    private ServerZombieMembership() {}

    public static boolean contains(ArrayList<?> list, IsoZombie zombie) {
        if (!CoreSettings.SERVER_ZOMBIE_MEMBERSHIP_HINTS || !GameServer.server
            || zombie == null || list.getClass() != ArrayList.class) return list.contains(zombie);
        int key = zombie.onlineId & 0xffff;
        int index = positions[key] - 1;
        // A saved index proves presence only after checking the current object identity.
        if (index >= 0 && index < list.size() && list.get(index) == zombie) return true;
        index = list.indexOf(zombie);
        positions[key] = index + 1;
        return index >= 0;
    }
}
