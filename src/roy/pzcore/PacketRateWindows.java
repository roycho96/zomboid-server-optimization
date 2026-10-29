package roy.pzcore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PacketRateWindows<K> {
    private final HashMap<K, Window> windows = new HashMap<>();
    private boolean hasTime;
    private long lastTime;
    private long lastCleanup;
    private K lastKey;

    public boolean check(K key, long now, int max, boolean client, boolean server) {
        if (hasTime && now < lastTime) {
            // Earlier expiry must stay irreversible when the wall clock moves back.
            pruneAll(lastTime);
            pruneAll(now);
            lastCleanup = now;
        } else if (!hasTime) {
            lastCleanup = now;
        } else if (now >= lastCleanup && now - lastCleanup >= 1000L) {
            // Bound inactive histories without scanning every type on every packet.
            pruneAll(now);
            lastCleanup = now;
        }
        Window window = windows.get(key);
        if (window == null) {
            window = new Window();
            windows.put(key, window);
        } else {
            window.prune(now);
        }
        if (client) {
            window.add(now);
        }
        boolean exceeded = window.size > max;
        if (!exceeded && server) {
            window.add(now);
        }
        lastTime = now;
        lastKey = key;
        hasTime = true;
        return exceeded;
    }

    public Map<K, Integer> counts(long now) {
        if (hasTime && now != lastTime) {
            throw new IllegalArgumentException("Use the timestamp of the last check");
        }
        HashMap<K, Integer> result = new HashMap<>();
        for (Map.Entry<K, Window> entry : windows.entrySet()) {
            // A timestamp just appended by check has not had another expiry pass.
            if (!Objects.equals(entry.getKey(), lastKey)) {
                entry.getValue().prune(now);
            }
            result.put(entry.getKey(), entry.getValue().size);
        }
        return result;
    }

    private void pruneAll(long now) {
        for (Window window : windows.values()) {
            window.prune(now);
        }
    }

    private static final class Window {
        private long[] timestamps = new long[8];
        private int head;
        private int size;

        private void prune(long now) {
            while (size != 0 && last() > now) {
                size--;
            }
            // Match the original signed-long timestamp + 1000 overflow too.
            while (size != 0 && last() > Long.MAX_VALUE - 1000L) {
                size--;
            }
            while (size != 0 && now > timestamps[head] + 1000L) {
                head = (head + 1) % timestamps.length;
                size--;
            }
            if (size == 0) {
                head = 0;
            }
            if (timestamps.length > 64 && size <= timestamps.length / 4) {
                int capacity = 8;
                while (capacity < (long)size * 2) capacity *= 2;
                long[] smaller = new long[capacity];
                int first = Math.min(size, timestamps.length - head);
                System.arraycopy(timestamps, head, smaller, 0, first);
                System.arraycopy(timestamps, 0, smaller, first, size - first);
                timestamps = smaller;
                head = 0;
            }
        }

        private long last() {
            return timestamps[(int)(((long)head + size - 1) % timestamps.length)];
        }

        private void add(long timestamp) {
            if (size == timestamps.length) {
                grow();
            }
            timestamps[(int)(((long)head + size) % timestamps.length)] = timestamp;
            size++;
        }

        private void grow() {
            int length = timestamps.length;
            int nextLength = length <= Integer.MAX_VALUE / 2 ? length * 2 : Integer.MAX_VALUE;
            if (nextLength == length) {
                throw new OutOfMemoryError("Packet rate window is full");
            }
            long[] next = new long[nextLength];
            int first = Math.min(size, length - head);
            System.arraycopy(timestamps, head, next, 0, first);
            System.arraycopy(timestamps, 0, next, first, size - first);
            timestamps = next;
            head = 0;
        }
    }
}
