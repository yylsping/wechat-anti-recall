package io.github.sandbox.wechatantirecall;

import android.app.Activity;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String LAUNCHER_ALIAS =
            "io.github.sandbox.wechatantirecall.LauncherAlias";

    private Button shortcutButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        TextView title = new TextView(this);
        title.setText("微信防撤回");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(7, 193, 96));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setTextSize(16);
        body.setTextColor(Color.DKGRAY);
        body.setLineSpacing(0, 1.35f);
        body.setMovementMethod(new ScrollingMovementMethod());
        body.setText(buildStatus());
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = pad;
        root.addView(body, bodyParams);

        shortcutButton = new Button(this);
        shortcutButton.setTextSize(16);
        shortcutButton.setTextColor(Color.WHITE);
        shortcutButton.setAllCaps(false);
        shortcutButton.setBackgroundTintList(
                ColorStateList.valueOf(Color.rgb(7, 193, 96)));
        shortcutButton.setOnClickListener(view -> toggleLauncherShortcut());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        root.addView(shortcutButton, buttonParams);

        refreshShortcutButton();
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shortcutButton != null) {
            refreshShortcutButton();
        }
    }

    private ComponentName launcherAlias() {
        return new ComponentName(this, LAUNCHER_ALIAS);
    }

    private boolean isLauncherShortcutEnabled() {
        int state = getPackageManager().getComponentEnabledSetting(launcherAlias());
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void toggleLauncherShortcut() {
        boolean enable = !isLauncherShortcutEnabled();
        getPackageManager().setComponentEnabledSetting(
                launcherAlias(),
                enable
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        refreshShortcutButton();
        Toast.makeText(
                this,
                enable ? "已显示桌面快捷方式" : "已隐藏桌面快捷方式",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshShortcutButton() {
        shortcutButton.setText(
                isLauncherShortcutEnabled() ? "隐藏桌面快捷方式" : "显示桌面快捷方式");
    }

    private String buildStatus() {
        String installed;
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.tencent.mm", 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            installed = info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            installed = "未安装";
        }
        return "目标微信：8.0.69 (3022)\n"
                + "当前微信：" + installed + "\n\n"
                + "启用方法：\n"
                + "1. 在 LSPosed 中启用本模块；\n"
                + "2. 作用域仅勾选微信；\n"
                + "3. 强制停止并重新打开微信。\n\n"
                + "桌面入口默认隐藏。需要时请从 LSPosed 手动打开本界面，"
                + "再使用下方按钮显示桌面快捷方式。\n\n"
                + "工作方式：\n"
                + "• 协议层继续执行微信原撤回处理；\n"
                + "• 业务层按会话与服务器消息 ID 定位原消息；\n"
                + "• 存储层仅保护他人发来的消息，普通消息路径完全不挂钩；\n"
                + "• 展示层显示“xxx尝试撤回一条消息”，末尾“一条消息”为微信原生蓝色点击片段；\n"
                + "• 点击“一条消息”会调用微信原生 ACTION_POSITION 路径定位原消息；\n"
                + "• 自己撤回时完全交回微信原生处理，不生成模块提示。\n\n"
                + "模块在微信 Application.attach 完成后立即安装业务 Hook，无固定延迟保护空窗。\n"
                + "原消息内容不会写入模块日志；提示写入在协议回调返回后异步执行。";
    }
}
