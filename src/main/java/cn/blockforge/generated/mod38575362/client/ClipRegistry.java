package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipMeta;
import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端这边的「服务器录像库快照」：服务端清单一到就存下来，
 * 录像库界面列片段、播放时判断「本机没有但服务器有」都读这里。
 *
 * <p>只做缓存不做磁盘操作，所以随时能被渲染线程调用。
 */
public final class ClipRegistry {
    /** 清单请求的最小间隔，界面开着也不会一直敲服务器。 */
    private static final long LIST_REQUEST_MS = 2500L;
    /** 单机不需要问服务器：本机目录就是服务器目录。 */
    private static final Map<String, ClipMeta> SERVER = new LinkedHashMap<>(16, 0.75F, true);
    private static long lastListRequestMs;
    private static boolean receivedList;
    /** 快照每变一次加一：录像库界面盯着它，变了就重新列一遍，不用自己傻傻轮询。 */
    private static long version;

    private ClipRegistry() {
    }

    /** 服务器快照的变更号（清单到达、单段更新、广播变化、断开清空都会让它跳）。 */
    public static synchronized long version() {
        return version;
    }

    public static synchronized List<ClipMeta> all() {
        return new ArrayList<>(SERVER.values());
    }

    public static synchronized ClipMeta find(String clip) {
        if (clip == null) {
            return null;
        }
        return SERVER.get(clip);
    }

    /** 收到整份清单。 */
    public static synchronized void onList(List<ClipMeta> clips) {
        SERVER.clear();
        for (ClipMeta meta : clips) {
            if (ClipWire.isSafeClipName(meta.id())) {
                SERVER.put(meta.id(), meta);
            }
        }
        receivedList = true;
        lastListRequestMs = 0L;
        version++;
    }

    /** 收到单段信息（null 表示服务器上也没有这一段）。 */
    public static synchronized void onMeta(ClipMeta meta) {
        if (meta != null && ClipWire.isSafeClipName(meta.id())) {
            SERVER.put(meta.id(), meta);
            ClipStore.dropInfo(meta.id());
            version++;
        }
    }

    /** 服务器广播：某段新增/更新了，或者被删掉了。 */
    public static synchronized void onChanged(String clip, int kind, ClipMeta meta) {
        if (!ClipWire.isSafeClipName(clip)) {
            return;
        }
        if (kind == 1) {
            SERVER.remove(clip);
            ClipStore.purgeServerCopy(clip);
            version++;
            return;
        }
        if (meta != null) {
            SERVER.put(clip, meta);
        }
        // 名片变了（体积、帧数），让 ClipStore 重新认一次本机文件。
        ClipStore.dropInfo(clip);
        version++;
    }

    /** 想要一份最新的服务器清单（内部节流，重复调用没关系）。 */
    public static void requestList() {
        requestList(false);
    }

    /** force 为真时立刻要（打开录像库界面的那一刻）；平时最多每 2.5 秒敲一次服务器。 */
    public static synchronized void requestList(boolean force) {
        // 每刻都会被客户端 tick 敲一次：没连接（主界面/掉线）时直接跳过，
        // 不然等于对着不存在的服务器发清单请求。
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastListRequestMs < LIST_REQUEST_MS) {
            return;
        }
        lastListRequestMs = now;
        ModNetworking.requestClipList();
    }

    /** 收到过清单（用来判断服务器是否真的支持这套录像同步）。 */
    public static synchronized boolean knowsServer() {
        return receivedList;
    }

    /** 服务器上一段都没有（或压根不支持同步）时也报一下状态，界面好提示玩家。 */
    public static synchronized int serverCount() {
        return SERVER.size();
    }

    /** 退出存档/断开服务器：把这份快照扔掉，别把上一个服务器的录像留给下一个。 */
    public static synchronized void clear() {
        SERVER.clear();
        receivedList = false;
        lastListRequestMs = 0L;
        version++;
    }
}
