package cn.blockforge.generated.mod38575362;

import cn.blockforge.generated.mod38575362.client.ClipFetcher;
import cn.blockforge.generated.mod38575362.client.ClipFileFetcher;
import cn.blockforge.generated.mod38575362.client.ClipRegistry;
import cn.blockforge.generated.mod38575362.client.ClipUploader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 服务端安全的转发层：服务器发来的录像库消息只在客户端处理。
 */
final class ClipClientProxy {
    private ClipClientProxy() {
    }

    static void handleList(List<ClipMeta> clips) {
        ClipRegistry.onList(clips);
    }

    static void handleMeta(ClipMeta meta) {
        ClipRegistry.onMeta(meta);
        if (meta != null && "mp4".equalsIgnoreCase(meta.format())) {
            ClipFileFetcher.ensureLocal(meta.id(), meta.videoBytes());
        }
    }

    static void handleFrame(String clip, int index, byte[] data) {
        // 优先：正在进行的 MP4 整文件下载
        if (ClipFileFetcher.onChunk(clip, index, data)) {
            return;
        }
        // 服务器上这段是 MP4 时，帧请求返回的也是文件块；绝不能当 JPG 落到磁盘。
        ClipMeta meta = ClipRegistry.find(clip);
        if (meta != null && "mp4".equalsIgnoreCase(meta.format())) {
            ClipFileFetcher.ensureLocal(clip, meta.videoBytes());
            ClipFileFetcher.onChunk(clip, index, data);
            return;
        }
        ClipFetcher.onFrame(clip, index, data);
    }

    static void handleAskOwner(String clip) {
        ClipUploader.onAskedByServer(clip);
    }

    static void handleGrant(String clip, int code, String reason) {
        ClipUploader.onGrant(clip, code, reason);
    }

    static void handleChanged(String clip, int kind, ClipMeta meta) {
        ClipRegistry.onChanged(clip, kind, meta);
    }

    static void handleNotice(String text) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), true);
        }
    }
}
