package com.tim.qzone.autolike;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 模块设置界面：开关 + 仅好友 + 延迟。
 * 写入模块自身偏好，宿主 TIM 进程通过 libxposed 远程偏好读取。
 * 日志/数据统一存放在 TIM 私有目录 DianZan 下。
 */
public class SettingsActivity extends Activity {

    static final String PREF = "qzone_autolike";
    static final String DIR = "/storage/emulated/0/Android/data/com.tencent.tim/DianZan";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);

        final int pad = (int) (getResources().getDisplayMetrics().density * 20);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("TIM 空间说说自动点赞");
        title.setTextSize(20f);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\n作用：浏览「好友动态 / 空间说说」时，刷到的说说若你还没有点赞，将自动为你点赞。\n\n"
                + "· 不会重复点赞已赞过的说说\n"
                + "· 不会给广告 / 假数据点赞\n"
                + "· 修改后需重启 TIM 生效\n");
        desc.setTextSize(14f);
        root.addView(desc);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;

        final Switch sw = new Switch(this);
        sw.setText("启用自动点赞");
        sw.setChecked(prefs.getBoolean("enabled", true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.edit().putBoolean("enabled", v).apply();
            }
        });
        root.addView(sw, lp);

        final Switch swFriend = new Switch(this);
        swFriend.setText("仅点赞好友");
        swFriend.setChecked(prefs.getBoolean("friends_only", true));
        swFriend.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.edit().putBoolean("friends_only", v).apply();
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = pad;
        root.addView(swFriend, lp2);

        TextView tip = new TextView(this);
        tip.setText("（开启后只赞互相关注的好友，跳过推荐 / 名人 / 陌生人动态）");
        tip.setTextSize(12f);
        root.addView(tip);

        TextView dl = new TextView(this);
        dl.setText("\n自动点赞延迟（毫秒，默认 100）：");
        dl.setTextSize(14f);
        root.addView(dl);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(prefs.getInt("delay_ms", 100)));
        root.addView(et);

        final TextView save = new TextView(this);
        save.setText("\n[ 保存 ]");
        save.setTextSize(16f);
        save.setPadding(0, pad, 0, pad);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int d = 100;
                try {
                    d = Integer.parseInt(et.getText().toString().trim());
                } catch (Throwable ignore) {
                }
                prefs.edit().putInt("delay_ms", Math.max(0, d)).apply();
                save.setText("\n[ 已保存（重启 TIM 生效）]");
            }
        });
        root.addView(save);

        // ---------------- 数据目录信息 ----------------
        final File dir = new File(DIR);
        final File log = new File(dir, "DianZan.log");

        TextView infoHead = new TextView(this);
        infoHead.setText("\n—— 模块数据 ——");
        infoHead.setTextSize(15f);
        infoHead.setPadding(0, pad, 0, 0);
        root.addView(infoHead);

        final TextView info = new TextView(this);
        info.setTextSize(12f);
        root.addView(info);

        final TextView refresh = new TextView(this);
        refresh.setText("\n[ 刷新状态 ]");
        refresh.setTextSize(15f);
        refresh.setPadding(0, pad / 2, 0, 0);
        root.addView(refresh);

        final TextView showLog = new TextView(this);
        showLog.setText("[ 查看点赞日志 ]");
        showLog.setTextSize(15f);
        showLog.setPadding(0, pad / 2, 0, pad);
        root.addView(showLog);

        final Runnable updateInfo = new Runnable() {
            @Override
            public void run() {
                info.setText("目录：\n" + DIR
                        + "\n\n文件：DianZan.log（满 200 行自动清空）"
                        + "\n大小：" + (log.exists() ? (log.length() + " 字节") : "尚未生成")
                        + "\n\n本应用读取：" + (log.canRead() ? "允许" : "受限（查看时会自动用 root 读取）"));
            }
        };
        updateInfo.run();
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateInfo.run();
            }
        });

        showLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog(log);
            }
        });

        setContentView(scroll);
    }

    /** 依次尝试：直接读 → root 读。任一成功即展示。 */
    private void showLogDialog(File log) {
        StringBuilder sb = new StringBuilder("路径：\n" + log.getAbsolutePath() + "\n\n");

        String content = readDirect(log);
        String how = "直接读取";
        if (content == null) {
            content = readWithRoot(log.getAbsolutePath());
            how = "root 读取";
        }
        if (content != null) {
            String[] lines = content.split("\n", -1);
            int total = 0;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].length() > 0) total++;
            }
            sb.append("读取方式：").append(how)
                    .append("（共 ").append(total).append(" 行）\n")
                    .append("--------------------------------\n");
            int shown = 0;
            for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
                shown++;
            }
            if (lines.length > 200) sb.append("...(仅显示最近 200 行)");
        } else {
            sb.append("无法读取该文件。\n\n")
                    .append("这是 TIM 的应用私有目录，普通应用无权限访问。\n")
                    .append("请用以下任一方式查看：\n")
                    .append("1) 文件管理器（如 MT 管理器）授予 Shizuku/root 后进入该路径；\n")
                    .append("2) 直接打开上面的完整路径。");
        }

        TextView tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setText(sb.toString());
        tv.setTextIsSelectable(true);
        int p = (int) (getResources().getDisplayMetrics().density * 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("DianZan.log")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String readDirect(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return slurp(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private String readWithRoot(String path) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            return slurp(p.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private String slurp(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
            if (++n > 5000) break;
        }
        return sb.toString();
    }

    private void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignore) {
        }
    }
}