package cn.blockforge.generated.mod38575362;

import cn.blockforge.generated.mod38575362.client.VideoPlayerScreen;
import net.minecraft.core.BlockPos;

/**
 * 服务端安全的转发层：只在客户端 lambda 里引用录像库界面，
 * 专用服务端不会加载任何客户端类。
 */
final class VideoPlayerClientProxy {
    private VideoPlayerClientProxy() {
    }

    static void openLibrary(BlockPos pos) {
        VideoPlayerScreen.open(pos);
    }
}
