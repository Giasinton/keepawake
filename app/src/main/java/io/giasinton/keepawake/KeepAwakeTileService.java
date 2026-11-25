package io.giasinton.keepawake;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心磁贴。
 *
 * <p>点按 = 开关；每次面板打开（onStartListening）先 {@link Keeper#recover}
 * 对齐状态再刷新显示，所以即使进程中途被系统回收、状态也始终显示正确。
 * 没有 ACTIVE_TILE meta：走经典监听模式，换取「打开面板必定同步」。
 */
public class KeepAwakeTileService extends TileService {

    @Override
    public void onTileAdded() {
        pushTileState();
    }

    @Override
    public void onStartListening() {
        Keeper.recover(this); // 打开控制中心 = 最佳自愈时机
        pushTileState();
    }

    @Override
    public void onClick() {
        Keeper.recover(this); // 先对齐（比如定时其实已过期 → 视为关闭）
        if (Keeper.isActive(this)) {
            Keeper.stopSession(this); // 关
        } else if (!Keeper.canDrawOverlays(this)) {
            // 首次使用：引导去开「显示在其他应用上层」，授权回来再点一次即可
            Keeper.launchOverlaySettings(this);
        } else {
            Keeper.startSession(this); // 开
        }
        pushTileState();
    }

    @Override
    public void onTileRemoved() {
        // 磁贴被移除 = 失去唯一开关入口，直接收尾
        Keeper.stopSession(this);
    }

    /** 把会话状态推给快速设置面板。 */
    private void pushTileState() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean active = Keeper.isActive(this);
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            // Tile.setSubtitle 是 API 29 才有的，低版本只显示主标签
            tile.setSubtitle(Policy.subtitle(active, Keeper.endAt(this), System.currentTimeMillis()));
        }
        tile.setContentDescription(getString(R.string.tile_label));
        tile.updateTile();
    }
}
