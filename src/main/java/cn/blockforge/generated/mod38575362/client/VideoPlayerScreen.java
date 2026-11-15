package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModNetworking;
import cn.blockforge.generated.mod38575362.VideoPlayerBlockEntity;
import cn.blockforge.generated.mod38575362.VideoProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 录像记录器界面（UI 整理版）：
 * 左列表 · 右详情 · 底部分组按钮 · 顶部状态条。
 */
public class VideoPlayerScreen extends Screen {

    private static final int ROW_H = 24;
    private static final int LIST_TOP = 36;
    private static final int COL_BG = 0xFF1A1D24;
    private static final int COL_PANEL = 0xFF22262F;
    private static final int COL_BORDER = 0xFF3A4150;
    private static final int COL_ACCENT = 0xFF4C8BF5;
    private static final int COL_OK = 0xFF3CB371;
    private static final int COL_WARN = 0xFFD4A017;
    private static final int COL_BAD = 0xFFE74C3C;
    private static final int COL_TEXT = 0xFFE8ECF1;
    private static final int COL_MUTED = 0xFF9AA3B2;
    private static final int COL_SELECT = 0xFF2A3A55;

    private final BlockPos pos;
    private List<ClipEntry> entries = List.of();
    private int selected = -1;
    private int scroll;
    private int listRows;
    private int listWidth;
    private int panelX;
    private int panelWidth;
    private int listBottom;
    private long listVersion = -1L;
    private long lastScanMs;
    private long deleteArmedAt = Long.MIN_VALUE;
    private String message = "";
    private long messageUntil;

    private Button playButton;
    private Button pauseButton;
    private Button clearButton;
    private Button deleteButton;
    private Button exportMp4Button;
    private Button exportWebmButton;
    private Button importButton;
    private Button refreshButton;

    public VideoPlayerScreen(BlockPos pos) {
        super(Component.translatable("block.mod_38575362.video_player"));
        this.pos = pos;
    }

    /** 由方块 use() 在客户端调用；专用服务端永远不会走到这里。 */
    public static void open(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof VideoPlayerBlockEntity) {
            minecraft.setScreen(new VideoPlayerScreen(pos));
        }
    }

    @Override
    protected void init() {
        // 打开界面这一刻就向服务器要一份最新清单（不等 2.5 秒的节流），
        // 别人上传的录像能马上出现在列表里。
        ClipRegistry.requestList(true);
        this.entries = scanClips();
        this.listVersion = ClipRegistry.version();
        this.lastScanMs = System.currentTimeMillis();
        this.listWidth = Math.min(220, Math.max(160, this.width / 3));
        this.panelX = 12 + this.listWidth + 8;
        this.panelWidth = Math.max(160, this.width - this.panelX - 12);

        // 底部：状态行 + 4 行按钮 + 边距
        int bottomReserve = 118;
        this.listBottom = this.height - bottomReserve;
        this.listRows = Math.max(3, (this.listBottom - LIST_TOP - 4) / ROW_H);

        int btnY = this.height - 100;
        int half = (this.panelWidth - 4) / 2;
        int gap = 22;

        this.playButton = addRenderableWidget(Button.builder(Component.literal("▶ 播放"), b -> playSelected())
                .bounds(this.panelX, btnY, half, 20).build());
        this.pauseButton = addRenderableWidget(Button.builder(Component.literal("Ⅱ 暂停"), b -> togglePause())
                .bounds(this.panelX + half + 4, btnY, this.panelWidth - half - 4, 20).build());

        this.clearButton = addRenderableWidget(Button.builder(Component.literal("■ 停止"), b -> clearDeck())
                .bounds(this.panelX, btnY + gap, half, 20).build());
        this.deleteButton = addRenderableWidget(Button.builder(Component.literal("删除"), b -> requestDelete())
                .bounds(this.panelX + half + 4, btnY + gap, this.panelWidth - half - 4, 20).build());

        this.exportMp4Button = addRenderableWidget(Button.builder(Component.literal("导出 MP4"), b -> exportClip(VideoExporter.Format.MP4))
                .bounds(this.panelX, btnY + gap * 2, half, 20).build());
        this.exportWebmButton = addRenderableWidget(Button.builder(Component.literal("导出 WebM"), b -> exportClip(VideoExporter.Format.WEBM))
                .bounds(this.panelX + half + 4, btnY + gap * 2, this.panelWidth - half - 4, 20).build());

        this.importButton = addRenderableWidget(Button.builder(Component.literal("导入视频"), b -> importVideos())
                .bounds(this.panelX, btnY + gap * 3, half, 20).build());
        this.refreshButton = addRenderableWidget(Button.builder(Component.literal("刷新列表"), b -> {
            ClipRegistry.requestList(true);
            this.entries = scanClips();
            this.listVersion = ClipRegistry.version();
            this.lastScanMs = System.currentTimeMillis();
            notify("列表已刷新（" + this.entries.size() + " 段）");
        }).bounds(this.panelX + half + 4, btnY + gap * 3, this.panelWidth - half - 4, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width - 72, 8, 60, 20).build());
    }

    private static List<ClipEntry> scanClips() {
        List<ClipEntry> list = new ArrayList<>();
        for (ClipStore.Clip clip : ClipStore.listClips()) {
            if (clip == null || clip.id == null) continue;
            list.add(new ClipEntry(
                    clip.id, clip.width, clip.height, clip.frameCount, clip.fps,
                    clip.profile, clip.durationMs, clip.sizeBytes(), clip.created,
                    clip.remote, clip.owner, 1f));
        }
        // 合并服务器名片
        for (var meta : ClipRegistry.all()) {
            if (meta == null) continue;
            boolean exists = false;
            for (ClipEntry e : list) {
                if (e.id.equals(meta.id())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                VideoProfile profile = VideoProfile.byId(meta.profileId());
                list.add(new ClipEntry(
                        meta.id(), meta.width(), meta.height(), meta.frames(), meta.fps(),
                        profile, meta.durationMs(), meta.bytes(), meta.created(),
                        true, meta.owner(), 0f));
            }
        }
        list.sort((a, b) -> Long.compare(b.created, a.created));
        return list;
    }

    private void playSelected() {
        ClipEntry entry = selectedEntry();
        if (entry == null) {
            notify("请先在左侧选择一段录像");
            return;
        }
        VideoPlayerBlockEntity be = blockEntity();
        if (be == null) {
            notify("找不到录像记录器方块");
            return;
        }
        ModNetworking.setDeckClip(this.pos, entry.id, entry.frames, entry.fps);
        notify("▶ 开始回放「" + displayName(entry) + "」");
    }

    private void togglePause() {
        VideoPlayerBlockEntity be = blockEntity();
        if (be == null || !be.hasClip()) {
            notify("当前没有在播放的内容");
            return;
        }
        ModNetworking.toggleDeckClip(this.pos);
        notify(be.isPlaying() ? "已暂停" : "已继续播放");
    }

    private void clearDeck() {
        VideoPlayerBlockEntity be = blockEntity();
        if (be == null || !be.hasClip()) {
            notify("屏幕本来就是空的");
            return;
        }
        ModNetworking.setDeckClip(this.pos, "", 0, 12);
        notify("已停止，屏幕清空（录像仍在库中）");
    }

    private void requestDelete() {
        ClipEntry entry = selectedEntry();
        if (entry == null) {
            notify("请先选择要删除的录像");
            return;
        }
        if (this.deleteArmedAt == Long.MIN_VALUE
                || System.currentTimeMillis() - this.deleteArmedAt > 4000L) {
            this.deleteArmedAt = System.currentTimeMillis();
            this.deleteButton.setMessage(Component.literal("确认删除"));
            notify(entry.remote
                    ? "再点一次确认：将从服务器删除"
                    : "再点一次确认：将从本机删除");
            return;
        }
        this.deleteArmedAt = Long.MIN_VALUE;
        this.deleteButton.setMessage(Component.literal("删除"));
        if (entry.remote) {
            ModNetworking.deleteClipFromServer(entry.id);
        } else {
            ClipStore.delete(entry.id);
        }
        this.entries = scanClips();
        this.selected = -1;
        notify("已删除「" + displayName(entry) + "」");
    }

    private void exportClip(VideoExporter.Format format) {
        ClipEntry entry = selectedEntry();
        if (entry == null) {
            notify("请先选择要导出的录像");
            return;
        }
        if (VideoExporter.isBusy() || EncodeSession.isBusy()) {
            notify("已有导出/编码任务，请稍候");
            return;
        }
        if (entry.remote && !ClipStore.hasLocal(entry.id)) {
            notify("本机没有帧文件，请先完整下载后再导出");
            return;
        }
        ClipStore.Clip clip = ClipStore.localClip(entry.id);
        if (clip == null) {
            notify("无法读取本机录像数据");
            return;
        }
        notify("正在导出 " + format.displayName + " …");
        VideoExporter.exportAsync(clip, format,
                file -> notify("✓ 导出成功：" + file.getName()),
                err -> notify("导出失败：" + err));
    }

    private void importVideos() {
        if (VideoImporter.isBusy() || VideoExporter.isBusy()) {
            notify("已有导入/导出任务，请稍候");
            return;
        }
        File root = VideoImporter.importRoot();
        notify("扫描导入目录：" + root.getAbsolutePath());
        VideoImporter.importAllAsync(
                this::notify,
                count -> {
                    this.entries = scanClips();
                    this.listVersion = ClipRegistry.version();
                    notify("✓ 成功导入 " + count + " 个视频");
                },
                this::notify);
    }

    private VideoPlayerBlockEntity blockEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        BlockEntity be = mc.level.getBlockEntity(this.pos);
        return be instanceof VideoPlayerBlockEntity v ? v : null;
    }

    private ClipEntry selectedEntry() {
        if (this.selected < 0 || this.selected >= this.entries.size()) return null;
        return this.entries.get(this.selected);
    }

    private void notify(String text) {
        this.message = text == null ? "" : text;
        this.messageUntil = System.currentTimeMillis() + 5000L;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - this.lastScanMs > 2000L || ClipRegistry.version() != this.listVersion) {
            int keep = this.selected >= 0 && this.selected < this.entries.size()
                    ? this.entries.get(this.selected).id.hashCode() : 0;
            this.entries = scanClips();
            this.listVersion = ClipRegistry.version();
            this.lastScanMs = now;
            // 尝试保持选中
            if (keep != 0) {
                for (int i = 0; i < this.entries.size(); i++) {
                    if (this.entries.get(i).id.hashCode() == keep) {
                        this.selected = i;
                        break;
                    }
                }
            }
        }
        if (this.deleteArmedAt != Long.MIN_VALUE && now - this.deleteArmedAt > 4000L) {
            this.deleteArmedAt = Long.MIN_VALUE;
            if (this.deleteButton != null) {
                this.deleteButton.setMessage(Component.literal("删除"));
            }
        }
        // 按钮可用性
        boolean hasSel = selectedEntry() != null;
        if (this.playButton != null) {
            this.playButton.active = hasSel;
            this.deleteButton.active = hasSel;
            this.exportMp4Button.active = hasSel && !VideoExporter.isBusy();
            this.exportWebmButton.active = hasSel && !VideoExporter.isBusy();
            this.importButton.active = !VideoImporter.isBusy();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        // 顶栏
        g.fill(0, 0, this.width, 28, 0xFF14171C);
        g.fill(0, 28, this.width, 29, COL_ACCENT);
        g.drawString(this.font, "录像记录器", 12, 10, COL_TEXT, false);
        g.drawString(this.font, this.entries.size() + " 段", 100, 10, COL_MUTED, false);

        // 顶部状态（编码/上传/下载）
        String status = buildStatusLine();
        if (status != null) {
            int sw = this.font.width(status);
            g.drawString(this.font, status, this.width - 80 - sw - 8, 10, COL_WARN, false);
        }

        // 左列表面板
        int listX = 8;
        int listY = LIST_TOP;
        fillPanel(g, listX, listY - 6, listX + this.listWidth, this.listBottom, COL_BG);
        g.drawString(this.font, "片库", listX + 8, listY - 2, COL_MUTED, false);

        int y = listY + 12;
        int end = Math.min(this.scroll + this.listRows, this.entries.size());
        for (int i = this.scroll; i < end; i++) {
            ClipEntry e = this.entries.get(i);
            int rowY = y + (i - this.scroll) * ROW_H;
            boolean sel = i == this.selected;
            boolean hover = mouseX >= listX && mouseX < listX + this.listWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
            int bg = sel ? COL_SELECT : (hover ? 0xFF2A2F3A : COL_PANEL);
            g.fill(listX + 4, rowY, listX + this.listWidth - 4, rowY + ROW_H - 3, bg);
            if (sel) {
                g.fill(listX + 4, rowY, listX + 6, rowY + ROW_H - 3, COL_ACCENT);
            }
            String name = trimToWidth(this.font, displayName(e), this.listWidth - 48);
            g.drawString(this.font, name, listX + 10, rowY + 3, COL_TEXT, false);
            String tag = e.remote ? "远程" : "本机";
            int tagColor = e.remote ? COL_WARN : COL_OK;
            g.drawString(this.font, tag, listX + this.listWidth - 8 - this.font.width(tag), rowY + 3, tagColor, false);
            String sub = e.width + "×" + e.height + " · " + e.fps + "fps · " + humanDuration(e.durationMs);
            g.drawString(this.font, trimToWidth(this.font, sub, this.listWidth - 16), listX + 10, rowY + 13, COL_MUTED, false);
        }
        if (this.entries.isEmpty()) {
            g.drawString(this.font, "暂无录像", listX + 12, listY + 40, COL_MUTED, false);
            g.drawString(this.font, "用摄像机录制或点「导入」", listX + 12, listY + 52, COL_MUTED, false);
        }
        if (this.entries.size() > this.listRows) {
            // 简易滚动提示
            g.drawString(this.font, "滚轮翻页", listX + 8, this.listBottom - 12, COL_MUTED, false);
        }

        // 右详情面板
        fillPanel(g, this.panelX - 4, listY - 6, this.panelX + this.panelWidth + 4, this.listBottom, COL_BG);
        g.drawString(this.font, "详情", this.panelX, listY - 2, COL_MUTED, false);

        ClipEntry sel = selectedEntry();
        int dy = listY + 14;
        if (sel == null) {
            g.drawString(this.font, "未选择录像", this.panelX, dy, COL_MUTED, false);
            g.drawString(this.font, "在左侧点选一段以查看信息", this.panelX, dy + 14, COL_MUTED, false);
            g.drawString(this.font, "并使用下方播放 / 导出等操作", this.panelX, dy + 28, COL_MUTED, false);
        } else {
            g.drawString(this.font, trimToWidth(this.font, displayName(sel), this.panelWidth - 8), this.panelX, dy, COL_TEXT, false);
            dy += 16;
            drawMeta(g, this.panelX, dy, "分辨率", sel.width + " × " + sel.height);
            dy += 12;
            drawMeta(g, this.panelX, dy, "帧率", sel.fps + " fps");
            dy += 12;
            drawMeta(g, this.panelX, dy, "时长", humanDuration(sel.durationMs) + "（" + sel.frames + " 帧）");
            dy += 12;
            drawMeta(g, this.panelX, dy, "体积", humanSize(sel.sizeBytes));
            dy += 12;
            drawMeta(g, this.panelX, dy, "档位", sel.profile != null ? sel.profile.label() : "—");
            dy += 12;
            drawMeta(g, this.panelX, dy, "位置", sel.remote ? "服务器" : "本机");
            dy += 12;
            if (!sel.owner.isEmpty()) {
                drawMeta(g, this.panelX, dy, "上传者", sel.owner);
                dy += 12;
            }
            if (sel.created > 0) {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(sel.created));
                drawMeta(g, this.panelX, dy, "时间", time);
                dy += 12;
            }
            dy += 6;
            boolean local = ClipStore.hasLocal(sel.id);
            boolean downloading = ClipFileFetcher.isDownloading(sel.id);
            String avail = downloading ? "下载中…" : (local ? "可播放 / 可导出" : "需下载后播放");
            int ac = downloading ? COL_WARN : (local ? COL_OK : COL_BAD);
            g.drawString(this.font, avail, this.panelX, dy, ac, false);
        }

        // 底部分隔与提示
        g.fill(8, this.listBottom + 2, this.width - 8, this.listBottom + 3, COL_BORDER);
        if (System.currentTimeMillis() < this.messageUntil && !this.message.isEmpty()) {
            String msg = trimToWidth(this.font, this.message, this.width - 24);
            g.fill(8, this.height - 16, 8 + this.font.width(msg) + 8, this.height - 4, 0xCC000000);
            g.drawString(this.font, msg, 12, this.height - 14, 0xFFB8F5C0, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawMeta(GuiGraphics g, int x, int y, String key, String val) {
        g.drawString(this.font, key, x, y, COL_MUTED, false);
        g.drawString(this.font, val, x + 48, y, COL_TEXT, false);
    }

    private void fillPanel(GuiGraphics g, int x0, int y0, int x1, int y1, int fill) {
        g.fill(x0, y0, x1, y1, fill);
        g.fill(x0, y0, x1, y0 + 1, COL_BORDER);
        g.fill(x0, y1 - 1, x1, y1, COL_BORDER);
        g.fill(x0, y0, x0 + 1, y1, COL_BORDER);
        g.fill(x1 - 1, y0, x1, y1, COL_BORDER);
    }

    private String buildStatusLine() {
        if (EncodeSession.isBusy()) {
            return EncodeSession.statusLine();
        }
        String up = ClipUploader.uploading();
        if (up != null && !up.isEmpty()) {
            return "上传 " + ClipUploader.uploadedFrames() + "/" + ClipUploader.uploadTotal();
        }
        // 简单：若有下载任务
        for (ClipEntry e : this.entries) {
            if (ClipFileFetcher.isDownloading(e.id)) {
                return "下载中 " + displayName(e);
            }
        }
        if (VideoExporter.isBusy()) {
            return "正在导出…";
        }
        if (VideoImporter.isBusy()) {
            return "正在导入…";
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int listX = 8;
            int y = LIST_TOP + 12;
            int end = Math.min(this.scroll + this.listRows, this.entries.size());
            for (int i = this.scroll; i < end; i++) {
                int rowY = y + (i - this.scroll) * ROW_H;
                if (mouseX >= listX && mouseX < listX + this.listWidth
                        && mouseY >= rowY && mouseY < rowY + ROW_H - 2) {
                    this.selected = i;
                    this.deleteArmedAt = Long.MIN_VALUE;
                    if (this.deleteButton != null) {
                        this.deleteButton.setMessage(Component.literal("删除"));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= 8 && mouseX < 8 + this.listWidth && mouseY >= LIST_TOP && mouseY < this.listBottom) {
            int maxScroll = Math.max(0, this.entries.size() - this.listRows);
            if (delta > 0) {
                this.scroll = Math.max(0, this.scroll - 1);
            } else if (delta < 0) {
                this.scroll = Math.min(maxScroll, this.scroll + 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static String displayName(ClipEntry e) {
        if (e == null) return "";
        String id = e.id;
        if (id.startsWith("clip_")) {
            return "录像 " + id.substring(Math.max(0, id.length() - 6));
        }
        if (id.startsWith("import_")) {
            return id.length() > 20 ? "导入 " + id.substring(7, Math.min(19, id.length())) : id;
        }
        return id.length() > 24 ? id.substring(0, 22) + "…" : id;
    }

    static String humanDuration(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        if (seconds < 60L) return seconds + " 秒";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + " 分 " + (seconds % 60) + " 秒";
        return (minutes / 60) + " 时 " + (minutes % 60) + " 分";
    }

    static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(sb.toString() + text.charAt(i)) > budget) break;
            sb.append(text.charAt(i));
        }
        return sb + ellipsis;
    }

    public static final class ClipEntry {
        public final String id;
        public final int width;
        public final int height;
        public final int frames;
        public final int fps;
        public final VideoProfile profile;
        public final long durationMs;
        public final long sizeBytes;
        public final long created;
        public final boolean remote;
        public final String owner;
        public final float cached;

        ClipEntry(String id, int width, int height, int frames, int fps, VideoProfile profile,
                  long durationMs, long sizeBytes, long created, boolean remote, String owner, float cached) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.frames = frames;
            this.fps = fps;
            this.profile = profile;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.created = created;
            this.remote = remote;
            this.owner = owner == null ? "" : owner;
            this.cached = cached;
        }
    }
}
