package roy.pzcore;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import zombie.network.GameServer;

public final class ZombieRequestIndex {
    private ZombieRequestIndex() {}

    public static IdentityHashMap<Object, Boolean> prepare(LinkedList<?> queue, int authCount) {
        if (!CoreSettings.SERVER_ZOMBIE_REQUEST_INDEX || !GameServer.server
            || authCount < 128 || queue == null || queue.getClass() != LinkedList.class) return null;
        var seen = new IdentityHashMap<Object, Boolean>(Math.max(queue.size(), authCount));
        for (Object value : queue) seen.put(value, Boolean.TRUE);
        return seen;
    }

    public static boolean containsOrRemember(LinkedList<?> queue, Object value, IdentityHashMap<Object, Boolean> seen) {
        if (seen == null) return queue.contains(value);
        // A missing entry is immediately followed by the original tail append.
        return seen.put(value, Boolean.TRUE) != null;
    }
}
