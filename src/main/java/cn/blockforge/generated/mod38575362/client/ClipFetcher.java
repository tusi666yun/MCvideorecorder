package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按需取帧：本机没有的帧向服务器要，要回来直接存进本机目录当缓存。
 *
 * <p>这里刻意不走「一次把整段下完」：录像可能几百兆，而玩家通常只看几秒，
 * 播到哪一帧取哪一帧既省流量也不挡主线程。同时在飞的请求数量有限制，
 * 免得服务器一卡、客户端就堆出几百个包。
 */
public final class ClipFetcher {
    /** 同时在飞的帧请求上限。 */
    private static final int MAX_INFLIGHT = 5;
    /** 每刻最多发几个帧请求（一帧几十 KB，等于最多 2 MB/秒的下行）。 */
    private static final int SENDS_PER_TICK = 3;
    /** 一个请求多久没回就当作丢了，允许重新问一次。 */
    private static final long RESEND_MS = 6000L;
    /** 向服务器问某段信息的最小间隔。 */
    private static final long INFO_REQUEST_MS = 1500L;

    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    /** key = 片段#帧号 → 发出去的时刻。 */
    private static final Map<String, Long> INFLIGHT = new HashMap<>();
    private static final Set<String> QUEUED = new HashSet<>();
    private static final Map<String, Long> LAST_INFO_REQUEST = new HashMap<>();

    private ClipFetcher() {
    }

    /** 一条待发的取帧请求。 */
    private record Request(String clip, int index) {
    }

    /** 播放或预取时缺这一帧就问一次；重复问会被丢掉。 */
    public static synchronized void requestFrame(String clip, int index) {
        if (!ClipWire.isSafeClipName(clip) || index < 0) {
            return;
        }
        String key = clip + '#' + index;
        if (INFLIGHT.containsKey(key) || QUEUED.contains(key)) {
            return;
        }
        QUEUED.add(key);
        QUEUE.add(new Request(clip, index));
    }

    /** 本机完全没有这段时，先向服务器问它的名片。 */
    public static synchronized void requestInfo(String clip) {
        if (!ClipWire.isSafeClipName(clip)) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_INFO_REQUEST.get(clip);
        if (last != null && now - last < INFO_REQUEST_MS) {
            return;
        }
        if (LAST_INFO_REQUEST.size() > 64) {
            LAST_INFO_REQUEST.clear();
        }
        LAST_INFO_REQUEST.put(clip, now);
        ModNetworking.requestClipInfo(clip);
    }

    /** 客户端刻里调用：把排队的请求发出去，并检查超时的请求。 */
    public static synchronized void tick() {
        // 没有连接就没有服务器可问：这一刻直接跳过（断开时队列已被清空，这里是兜底）。
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!INFLIGHT.isEmpty()) {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Long> entry : INFLIGHT.entrySet()) {
                if (now - entry.getValue() > RESEND_MS) {
                    expired.add(entry.getKey());
                }
            }
            for (String key : expired) {
                INFLIGHT.remove(key);
                QUEUED.remove(key);
            }
        }
        int sent = 0;
        while (!QUEUE.isEmpty() && INFLIGHT.size() < MAX_INFLIGHT && sent < SENDS_PER_TICK) {
            Request request = QUEUE.poll();
            if (request == null) {
                break;
            }
            String key = request.clip() + '#' + request.index();
            QUEUED.remove(key);
            INFLIGHT.put(key, now);
            ModNetworking.requestClipFrame(request.clip(), request.index());
            sent++;
        }
    }

    /**
     * 服务器回了帧数据：空数组表示服务器现在也拿不到（它已经去问谁本地有了）。
     * 存盘后什么都不用再做，ClipStore 下一帧就能从本地文件里读到它。
     */
    public static void onFrame(String clip, int index, byte[] data) {
        String key = clip + '#' + index;
        synchronized (ClipFetcher.class) {
            INFLIGHT.remove(key);
        }
        if (data != null && data.length > 0) {
            ClipStore.writeServerFrame(clip, index, data);
        }
    }

    /** 换片、删片、断线：把这段相关的在飞请求全清掉。 */
    public static synchronized void forget(String clip) {
        INFLIGHT.keySet().removeIf(key -> key.startsWith(clip + '#'));
        QUEUED.removeIf(key -> key.startsWith(clip + '#'));
        QUEUE.removeIf(request -> request.clip().equals(clip));
        LAST_INFO_REQUEST.remove(clip);
    }

    /** 断开服务器：清干净，别把上一个服务器的请求发给下一个。 */
    public static synchronized void clear() {
        INFLIGHT.clear();
        QUEUED.clear();
        QUEUE.clear();
        LAST_INFO_REQUEST.clear();
    }
}
