package com.tim.qzone.autolike;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * TIM 空间说说「刷到自动点赞」模块（libxposed 新 API 格式）。
 *
 * 设计参考 WeKit 的「声明式功能 + 运行时解析宿主类」思路，
 * 针对 TIM(QZone) 的点赞链路做了具体适配：
 *
 *   刷到说说  -> QZoneBaseFeedItemView.O(feed,pos) / QZoneBaseFeedAdapter.getView(...)
 *   是否已赞  -> feed.getLikeInfo().isLiked
 *   是否可赞  -> feed.getLocalInfo().canLike
 *   点赞标识  -> feed.getFeedCommInfo().curlikekey
 *   发送点赞  -> QZoneWriteOperationService.v0().u1(null, m)
 *
 * 数据目录：/storage/emulated/0/Android/data/com.tencent.tim/DianZan/
 * （与 QFun 模块一致，写在 TIM 自己的外部私有目录下）
 */
public class MainHook extends XposedModule {

    static final String TAG = "QZoneAutoLike";
    static final String TIM = "com.tencent.tim";

    /** 模块数据目录：TIM 外部私有目录下的 DianZan 文件夹。 */
    static final String DATA_DIR =
            "/storage/emulated/0/Android/data/com.tencent.tim/DianZan";

    static MainHook sInstance;
    static SharedPreferences sPrefs;
    static final Handler UI = new Handler(Looper.getMainLooper());

    /** 已经自动赞过的 curlikekey，避免同一说说重复发送。 */
    static final HashSet<String> DONE = new HashSet<String>();
    /** 已经打印过详情的 curlikekey，避免同一说说的日志把配额刷爆。 */
    static final HashSet<String> SEEN = new HashSet<String>();

    /** 只有主进程写文件日志，避免多进程并发写导致行数错乱。 */
    static boolean sMainProcess;

    public MainHook() {
        super();
        sInstance = this;
    }

    // ------------------------------------------------------------------
    // 文件日志（写入 DianZan 目录，满 200 行自动清空）
    // ------------------------------------------------------------------

    static final int MAX_LOG_LINES = 200;
    static File sLogFile;

    /** 解析出日志文件（首次调用时创建目录）。 */
    static File logFile() {
        if (sLogFile != null) return sLogFile;
        String[] candidates = new String[]{
                DATA_DIR,
                Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/Android/data/com.tencent.tim/DianZan",
                "/sdcard/Android/data/com.tencent.tim/DianZan",
        };
        for (String dir : candidates) {
            try {
                File d = new File(dir);
                if (!d.exists()) d.mkdirs();
                if (d.isDirectory() && d.canWrite()) {
                    sLogFile = new File(d, "DianZan.log");
                    return sLogFile;
                }
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    /** 静态上下文里写 LSPosed 日志的包装（log() 是实例方法）。 */
    static void slog(int prio, String tag, String msg) {
        MainHook self = sInstance;
        if (self != null) {
            try {
                self.log(prio, tag, msg);
            } catch (Throwable ignore) {
            }
        }
    }

    static void fileLog(String msg) {
        if (!sMainProcess) return;
        try {
            File f = logFile();
            if (f == null) return;
            // 满 200 行则整文件清空（自动滚动重置）
            if (f.exists() && countLines(f) >= MAX_LOG_LINES) {
                f.delete();
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((ts + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    /** 统计文件行数（用于日志上限判断）。 */
    static int countLines(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n, lines = 0;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') lines++;
                }
            }
            return lines;
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 判断当前是否运行在 TIM 主进程（决定要不要写文件日志）。 */
    static boolean isMainProcess() {
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            String name = new String(buf, 0, end, "UTF-8");
            return TIM.equals(name);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sMainProcess = isMainProcess();
        fileLog("=== module loaded process=" + param.getProcessName() + " ===");
        log(4, TAG, "onModuleLoaded process=" + param.getProcessName()
                + " main=" + sMainProcess);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!TIM.equals(pkg)) return;
        ClassLoader cl = param.getClassLoader();
        sMainProcess = isMainProcess();
        fileLog("onPackageReady " + pkg);
        log(4, TAG, "onPackageReady " + pkg);
        try {
            sPrefs = getRemotePreferences("qzone_autolike");
            if (sPrefs != null) sPrefs.getAll();
        } catch (Throwable t) {
            log(5, TAG, "remote prefs unavailable: " + t);
        }
        install(cl);
    }

    // ------------------------------------------------------------------
    // Hook 安装
    // ------------------------------------------------------------------

    void install(ClassLoader cl) {
        hookItemViewBind(cl);
        hookListAdapter(cl);
        hookLikeSender(cl);
    }

    /** 新架构：列表项绑定数据时触发。
     *
     * ⚠️ 注意：绑定 ≠ 刷到。RecyclerView 会预绑定屏幕外的项，
     * 因此这里**只登记候选**，真正点赞交给可见性轮询（见 ensurePoller）。 */
    void hookItemViewBind(final ClassLoader cl) {
        try {
            Class<?> itemView = Class.forName(
                    "com.qzone.reborn.feedx.itemview.QZoneBaseFeedItemView", false, cl);
            Class<?> feed = Class.forName(
                    "com.qzone.proxy.feedcomponent.model.BusinessFeedData", false, cl);
            Method m = itemView.getDeclaredMethod("O", feed, int.class);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        // thisObject 就是 itemView 实例
                        onFeedBound(chain.getArg(0), (View) chain.getThisObject(), cl);
                    } catch (Throwable ignore) {
                    }
                    return result;
                }
            });
            fileLog("hooked QZoneBaseFeedItemView.O");
            log(4, TAG, "hooked QZoneBaseFeedItemView.O");
        } catch (Throwable t) {
            fileLog("hook itemview FAILED: " + t);
            log(5, TAG, "hook itemview failed: " + t);
        }
    }

    /** 旧架构：列表 getView（同样是「绑定」，不是「刷到」）。 */
    void hookListAdapter(final ClassLoader cl) {
        try {
            Class<?> adapter = Class.forName(
                    "com.qzone.common.activities.base.QZoneBaseFeedAdapter", false, cl);
            Method m = adapter.getDeclaredMethod("getView", int.class,
                    android.view.View.class, android.view.ViewGroup.class);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object data = callMethod(chain.getThisObject(), "f", chain.getArg(0));
                        if (data != null && result instanceof View) {
                            onFeedBound(data, (View) result, cl);
                        }
                    } catch (Throwable ignore) {
                    }
                    return result;
                }
            });
            fileLog("hooked QZoneBaseFeedAdapter.getView");
            log(4, TAG, "hooked QZoneBaseFeedAdapter.getView");
        } catch (Throwable t) {
            fileLog("hook adapter FAILED: " + t);
            log(5, TAG, "hook adapter failed: " + t);
        }
    }

    /** 观察点赞请求，记录已赞，便于去重。 */
    void hookLikeSender(final ClassLoader cl) {
        try {
            Class<?> svc = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService", false, cl);
            Class<?> m = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService$m", false, cl);
            Method um = svc.getDeclaredMethod("u1", Handler.class, m);
            hook(um).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object mv = chain.getArg(1);
                        String key = (String) get(mv, "b");
                        boolean like = Boolean.TRUE.equals(get(mv, "e"));
                        if (like && !TextUtils.isEmpty(key)) {
                            synchronized (DONE) {
                                DONE.add(key);
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                    return chain.proceed();
                }
            });
            fileLog("hooked QZoneWriteOperationService.u1");
            log(4, TAG, "hooked QZoneWriteOperationService.u1");
        } catch (Throwable t) {
            fileLog("hook likeSender FAILED: " + t);
            log(5, TAG, "hook likeSender failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 配置读取（来自模块设置界面的远程偏好）
    // ------------------------------------------------------------------

    /** 默认开启，装上即生效；可在模块设置界面里关掉。 */
    static boolean enabled() {
        try {
            if (sPrefs != null && sPrefs.contains("enabled")) {
                return sPrefs.getBoolean("enabled", true);
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    static int delayMs() {
        try {
            if (sPrefs != null) return sPrefs.getInt("delay_ms", 100);
        } catch (Throwable ignore) {
        }
        return 100;
    }

    /** 是否只点赞好友（默认开启）。 */
    static boolean friendsOnly() {
        try {
            if (sPrefs != null && sPrefs.contains("friends_only")) {
                return sPrefs.getBoolean("friends_only", true);
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 好友判断
    //
    // TIM 里 getManager(int) 定义在 mqq.app.AppRuntime 上，
    // 而 BaseApplicationImpl 只是 Application(MobileQQ) 的子类，
    // 因此必须先拿到 AppRuntime：app.getRuntime().getManager(idx)。
    // ------------------------------------------------------------------

    static boolean sTriedFriendMgr;
    static Object sFriendMgr;

    /** 取得 TIM 的 FriendsManager（反射，找不到返回 null）。 */
    static Object friendManager(ClassLoader cl) {
        if (sTriedFriendMgr) return sFriendMgr;
        sTriedFriendMgr = true;

        int idx = -1;
        try {
            Class<?> qmf = Class.forName(
                    "com.tencent.mobileqq.app.QQManagerFactory", false, cl);
            idx = qmf.getField("FRIENDS_MANAGER").getInt(null);
        } catch (Throwable t) {
            slog(5, TAG, "FRIENDS_MANAGER index failed: " + t);
        }

        Object app = null;
        try {
            Class<?> bai = Class.forName(
                    "com.tencent.common.app.BaseApplicationImpl", false, cl);
            app = bai.getMethod("getApplication").invoke(null);
        } catch (Throwable ignore) {
        }
        if (app == null) {
            try {
                Class<?> mqq = Class.forName("mqq.app.MobileQQ", false, cl);
                app = mqq.getMethod("getMobileQQ").invoke(null);
            } catch (Throwable ignore) {
            }
        }

        ArrayList<Object> candidates = new ArrayList<Object>();
        if (app != null) {
            candidates.add(app);
            try {
                Object rt = callMethod(app, "getRuntime");
                if (rt != null) candidates.add(rt);
            } catch (Throwable ignore) {
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            Object mgr = tryGetManager(candidates.get(i), idx);
            if (mgr != null) {
                sFriendMgr = mgr;
                slog(4, TAG, "friendManager ok via #" + i + " = "
                        + mgr.getClass().getName());
                return mgr;
            }
        }
        slog(5, TAG, "friendManager unavailable (idx=" + idx
                + ", candidates=" + candidates.size() + ")");
        return null;
    }

    /** 在候选对象（含父类）上找 getManager(int) 并调用，返回非空结果。 */
    static Object tryGetManager(Object obj, int idx) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                Method[] ms = c.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    Method m = ms[i];
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getName().equals("getManager")
                            && ps.length == 1 && ps[0] == int.class) {
                        m.setAccessible(true);
                        Object r = m.invoke(obj, idx);
                        if (r != null) return r;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 该 uin 是否为我的 QQ 好友。判断不了时返回 null（调用方决定放行/拦截）。 */
    static Boolean isQQFriend(long uin, ClassLoader cl) {
        if (uin <= 0) return null;
        Object mgr = friendManager(cl);
        if (mgr == null) return null;
        // e0 = isFriend(String)，g0 = isFriend(String,false)，均为 TIM 4.1.0 未混淆方法
        String[] names = new String[]{"e0", "g0"};
        for (int i = 0; i < names.length; i++) {
            try {
                Method m = findStringMethod(mgr.getClass(), names[i]);
                if (m == null) continue;
                Object r = m.invoke(mgr, Long.toString(uin));
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    static Method findStringMethod(Class<?> c, String name) {
        while (c != null) {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals(name) && ps.length == 1
                        && ps[0] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 兜底启发式：QZone 没有单一的 isFriend 字段，
     * 广告/推荐/可能认识的人/名人主页/需加好友按钮，任一命中即判定为非好友。
     * 仅在拿不到 FriendManager 时使用。
     */
    static boolean isFriendFeed(Object feed) {
        try {
            if (Boolean.TRUE.equals(callMethod(feed, "isAdFeeds"))) return false;
            if (Boolean.TRUE.equals(callMethod(feed, "isGDTAdvFeed"))) return false;
            Object comm = callMethod(feed, "getFeedCommInfo");
            if (comm == null) return false;
            if (Boolean.TRUE.equals(get(comm, "needShowFollowBtn"))) return false;
            Object v;
            v = callMethod(comm, "isRecommendFriendsFeed");
            if (v instanceof Boolean && ((Boolean) v)) return false;
            v = callMethod(comm, "isMayKnowFriendFeed");
            if (v instanceof Boolean && ((Boolean) v)) return false;
            v = callMethod(comm, "isFamousSpaceUserFeed");
            if (v instanceof Boolean && ((Boolean) v)) return false;
            v = callMethod(comm, "isNeedAddFriendBtn");
            if (v instanceof Boolean && ((Boolean) v)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 自动点赞逻辑（v1.6）
    //
    // 需求：**看到那条说说的点赞按钮，就等约 300ms 后直接点赞**；
    //       如果 300ms 还没到就划到更下面的内容了，也依然完成点赞。
    //
    // 因此判定时机 = 「这个列表项的点赞按钮出现在屏幕上」，
    // 而不是「列表项被绑定数据」（绑定会预取屏幕外的项，导致提前点赞）。
    //
    // 触发后即从候选池移除并立即排入 300ms 延时队列，
    // **不再检查它是否还在屏幕上** —— 划走也照发。
    // ------------------------------------------------------------------

    /** 单个候选说说。 */
    static final class Candidate {
        final WeakReference<View> viewRef;
        final Object feed;
        final String key;
        final long uin;
        /** 是否已经排入点赞队列（避免同一项重复排队） */
        boolean queued;
        /** 候选创建时间（用于清理从未可见的僵尸候选） */
        final long createdAt;

        Candidate(View v, Object feed, String key, long uin) {
            this.viewRef = new WeakReference<View>(v);
            this.feed = feed;
            this.key = key;
            this.uin = uin;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** 候选池：key -> Candidate（弱引用 View，随滚动自动失效） */
    static final java.util.LinkedHashMap<String, Candidate> PENDING =
            new java.util.LinkedHashMap<String, Candidate>();

    /** 轮询是否已启动 */
    static boolean sPolling;

    static final long POLL_INTERVAL_MS = 60L;

    /**
     * 「刷到」判定：View 是否已经出现在屏幕上（含点赞按钮所在区域）。
     *
     * 用 getGlobalVisibleRect 拿到真正可见的矩形；只要该列表项
     * **有可见部分**（露出高度达到阈值）就认为「刷到了」。
     */
    static boolean isViewVisible(View v) {
        if (v == null) return false;
        if (!v.isShown()) return false;                 // attached + visible
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;

        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r)) return false;   // 完全在屏幕外
        if (r.height() <= 0) return false;

        float ratio = r.height() / (float) v.getHeight();
        return ratio >= visibleRatio();
    }

    /** 露出比例阈值，默认 0.1（露出 10% 高度即算刷到，因为要看到点赞按钮）。 */
    static float visibleRatio() {
        try {
            if (sPrefs != null) {
                int p = sPrefs.getInt("visible_percent", 10);
                return Math.max(5, Math.min(100, p)) / 100f;
            }
        } catch (Throwable ignore) {
        }
        return 0.1f;
    }

    /** 点赞延时（毫秒），默认 300。 */
    static int likeDelayMs() {
        try {
            if (sPrefs != null) return Math.max(0, sPrefs.getInt("like_delay_ms", 300));
        } catch (Throwable ignore) {
        }
        return 300;
    }

    // ------------------------------------------------------------------
    // 页面判定（v1.7 新增）
    //
    // 需求：**只给「动态说说」列表点赞**。
    //       从好友个人资料主页点进「他的动态」时，那里也复用了同一套
    //       QZoneBaseFeedItemView 渲染 → 会被误赞，必须排除。
    //
    // 实测类结构（TIM 4.1.0，dex 扫描确认）：
    //   com.qzone.reborn.base.QZoneBaseFeedxFragment            ← 共同基类
    //     ├─ com.qzone.reborn.feedx.fragment.QZoneFriendFeedXFragment      ✅ 好友动态（要赞）
    //     ├─ com.qzone.reborn.feedx.fragment.QZoneFeedxUserHomeFragment    ❌ 个人主页（不赞）
    //     ├─ com.qzone.reborn.feedx.fragment.QZoneFeedxDIYUserHomeFragment ❌ 个人主页 DIY
    //     └─ com.qzone.reborn.feedx.fragment.QZoneFeedxFamousUserHomeFragment ❌ 名人主页
    //
    // 判定方式：从 itemView 向上遍历 View 树，找到所属 Fragment，
    //          只有 **QZoneFriendFeedXFragment** 才允许点赞。
    // ------------------------------------------------------------------

    /** 允许点赞的页面类名（好友动态）。 */
    static final String[] LIKE_ALLOWED_PAGES = new String[]{
            "com.qzone.reborn.feedx.fragment.QZoneFriendFeedXFragment",
    };

    /** 明确排除的页面（个人主页 / DIY / 名人主页 / 详情页等）。 */
    static final String[] LIKE_BLOCKED_PAGES = new String[]{
            "com.qzone.reborn.feedx.fragment.QZoneFeedxUserHomeFragment",
            "com.qzone.reborn.feedx.fragment.QZoneFeedxDIYUserHomeFragment",
            "com.qzone.reborn.feedx.fragment.QZoneFeedxFamousUserHomeFragment",
            "com.qzone.reborn.feedx.fragment.QZoneFeedXDetailFragment",
            "com.qzone.reborn.feedx.fragment.QZoneFeedXMoodListFragment",
            "com.qzone.reborn.aio.QZoneAIOUserFeedxFragment",
    };

    /**
     * 判断当前 itemView 所属页面是否允许点赞。
     *
     * 策略：从 View 向上找 Fragment（通过 Fragment 的 getView() 建立映射）；
     *       拿到页面对象后按类名判断。
     *
     * ⚠️ 拿不到页面信息时**返回 false（不点赞）**，宁可不赞也不误赞 ——
     *    因为用户明确要求「只点赞动态说说」。
     */
    static boolean isLikeAllowedPage(View itemView) {
        if (itemView == null) return false;
        try {
            Object page = findOwnerFragment(itemView);

            // 拿不到 Fragment：降级用宿主 Activity 判断
            if (page == null) {
                Object act = findHostActivity(itemView);
                if (act == null) return false;
                String an = act.getClass().getName();
                if (an.contains("QZoneFriendFeedXActivity")) return true;
                return false;
            }

            String cls = page.getClass().getName();

            // ① 排除名单优先（个人主页 / DIY / 名人主页 / 详情页等）
            for (String b : LIKE_BLOCKED_PAGES) {
                if (cls.equals(b)) return false;
            }
            // ② 允许名单（好友动态）
            for (String a : LIKE_ALLOWED_PAGES) {
                if (cls.equals(a)) return true;
            }
            // ③ 其它未列入的页面：宁可不赞，避免误赞
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录一次页面判定的结果（用于排查，同一页面只记一次）。 */
    static final HashSet<String> PAGE_LOGGED = new HashSet<String>();

    static void logPageOnce(String pageName, boolean allowed) {
        try {
            String k = pageName + "/" + allowed;
            synchronized (PAGE_LOGGED) {
                if (!PAGE_LOGGED.add(k)) return;
            }
            fileLog("page=" + pageName + " likeAllowed=" + allowed);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 从 View 向上找所属 Fragment。
     *
     * 做法（按可靠性排序）：
     *   ① 【主】拿到 View 树的**根 View**，再到宿主 Activity 的
     *      FragmentManager 里反查「getView() == 根 View」的那个 Fragment。
     *      这是 androidx 的标准做法，最可靠。
     *   ② 【备】检查 View 自身及其父链上的 tag。
     *
     * ⚠️ 工程无 androidx 依赖，故全部走**反射**调用。
     * 拿不到时返回 null（调用方降级到 Activity 判断）。
     */
    static Object findOwnerFragment(View v) {
        // ① 主路径：根 View ←→ FragmentManager 反查
        try {
            View root = v;
            int guard = 0;
            while (root.getParent() instanceof View && guard++ < 100) {
                root = (View) root.getParent();
            }
            Object act = findHostActivity(v);
            if (act != null && root != null) {
                Object fm = invokeAny(act, "getSupportFragmentManager");
                if (fm == null) fm = invokeAny(act, "getFragmentManager");
                if (fm != null) {
                    Object frags = invokeAny(fm, "getFragments");
                    if (frags instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) frags;
                        for (int i = 0; i < list.size(); i++) {
                            Object f = list.get(i);
                            if (f == null) continue;
                            Object fv = invokeAny(f, "getView");
                            if (fv == root) return f;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }

        // ② 备路径：View 及其父链的 tag
        try {
            View cur = v;
            int guard = 0;
            while (cur != null && guard++ < 30) {
                Object tag = cur.getTag();
                if (tag != null) {
                    String n = tag.getClass().getName();
                    if (n.contains("Fragment")) return tag;
                }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignore) {
        }

        return null;
    }

    /** 反射调用无参方法，沿父类查找；失败返回 null。 */
    static Object invokeAny(Object obj, String name) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                Method[] ms = c.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    Method m = ms[i];
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        Object r = m.invoke(obj);
                        if (r != null) return r;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 从 View 的 context 链里取出宿主 Activity。 */
    static Object findHostActivity(View v) {
        try {
            android.content.Context c = v.getContext();
            int guard = 0;
            while (c instanceof android.content.ContextWrapper && guard++ < 20) {
                if (c instanceof android.app.Activity) return c;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 登记候选（绑定阶段调用，**不**立即点赞）。 */
    static void onFeedBound(Object feed, View itemView, ClassLoader cl) {
        if (feed == null || itemView == null || !enabled()) return;
        try {
            // ⭐ 页面过滤：只处理「好友动态」页，个人主页等一律跳过
            Object owner = findOwnerFragment(itemView);
            Object act = owner != null ? owner : findHostActivity(itemView);
            String pageName = act != null ? act.getClass().getName() : "(unknown)";
            boolean allowed = isLikeAllowedPage(itemView);
            logPageOnce(pageName, allowed);
            if (!allowed) {
                return;
            }

            Object comm = callMethod(feed, "getFeedCommInfo");
            final String key = comm == null ? null : (String) get(comm, "curlikekey");
            if (TextUtils.isEmpty(key)) return;

            synchronized (DONE) {
                if (DONE.contains(key)) return;      // 已赞 / 已判定跳过
            }
            synchronized (PENDING) {
                Candidate old = PENDING.get(key);
                if (old == null) {
                    long uin = 0L;
                    try {
                        Object u = callMethod(feed, "getUser");
                        uin = u == null ? 0L : ((Number) get(u, "uin")).longValue();
                    } catch (Throwable ignore) {
                    }
                    PENDING.put(key, new Candidate(itemView, feed, key, uin));
                } else if (old.viewRef.get() != itemView) {
                    // View 被复用给了别的数据，替换引用
                    PENDING.put(key, new Candidate(itemView, feed, key, old.uin));
                }
            }
            ensurePoller(cl);
        } catch (Throwable t) {
            if (sInstance != null) sInstance.log(5, TAG, "onFeedBound failed: " + t);
        }
    }

    /** 启动可见性轮询（幂等）。 */
    static void ensurePoller(final ClassLoader cl) {
        if (sPolling) return;
        sPolling = true;
        UI.post(new Runnable() {
            @Override
            public void run() {
                try {
                    pollOnce(cl);
                } catch (Throwable ignore) {
                }
                synchronized (PENDING) {
                    if (PENDING.isEmpty()) {
                        sPolling = false;
                        return;
                    }
                }
                UI.postDelayed(this, POLL_INTERVAL_MS);
            }
        });
    }

    /** 一次轮询：找出已经出现在屏幕上的候选，排入 300ms 延时队列。 */
    static void pollOnce(ClassLoader cl) {
        ArrayList<Candidate> ready = new ArrayList<Candidate>();
        long now = System.currentTimeMillis();

        synchronized (PENDING) {
            Iterator<java.util.Map.Entry<String, Candidate>> it =
                    PENDING.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Candidate> e = it.next();
                Candidate c = e.getValue();
                View v = c.viewRef.get();
                if (v == null) {
                    it.remove();                    // View 已被回收
                    continue;
                }
                if (now - c.createdAt > 30000L) {
                    it.remove();                    // 30s 都没刷到，丢弃
                    continue;
                }
                if (isViewVisible(v)) {
                    it.remove();                    // 刷到了 → 出池，后面不再看它
                    ready.add(c);
                }
            }
        }

        for (int i = 0; i < ready.size(); i++) {
            queueLike(ready.get(i), cl);
        }
    }

    /**
     * 排入点赞队列：**延迟 likeDelayMs()（默认 300ms）后发送**。
     *
     * ⚠️ 排入后不再关心该项是否还在屏幕上 —— 即使这 300ms 内
     * 用户划到了更下面的内容，点赞依然会照常发出。
     */
    static void queueLike(final Candidate c, final ClassLoader cl) {
        // 立即完成「好友 / 已赞 / 可赞」判定（此时数据最完整）
        if (!precheck(c, cl)) return;

        synchronized (DONE) {
            if (DONE.contains(c.key)) return;
            DONE.add(c.key);
        }

        boolean firstLog;
        synchronized (SEEN) {
            firstLog = SEEN.add(c.key);
        }
        if (firstLog) {
            fileLog("seen like button, like in " + likeDelayMs()
                    + "ms uin=" + c.uin + " key=" + c.key);
        }

        final Object f = c.feed;
        final String key = c.key;
        final MainHook self = sInstance;
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 到点就发，不检查是否还在屏幕内
                if (self != null) self.sendLike(f, key, cl);
            }
        }, likeDelayMs());
    }

    /** 点赞前校验（好友 / 已赞 / 可赞 / 假数据）。返回 true 表示可以点赞。 */
    static boolean precheck(Candidate c, ClassLoader cl) {
        // 仅好友过滤
        if (friendsOnly()) {
            Boolean isFrd = isQQFriend(c.uin, cl);
            if (isFrd == null) {
                if (!isFriendFeed(c.feed)) return false;
            } else if (!isFrd.booleanValue()) {
                return false;
            }
        }

        Object likeInfo = callMethodQuiet(c.feed, "getLikeInfo");
        if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isLiked"))) {
            return false;                                   // 已赞
        }
        Object localInfo = callMethodQuiet(c.feed, "getLocalInfo");
        if (localInfo != null && Boolean.FALSE.equals(get(localInfo, "canLike"))) {
            return false;                                   // 不可赞（数据未就绪）
        }
        if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isFakeOperation"))) {
            return false;                                   // 本地假数据
        }
        return true;
    }

    static Object callMethodQuiet(Object obj, String name, Object... args) {
        try {
            return callMethod(obj, name, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射构造 QZoneWriteOperationService$m 并调用 u1 发送点赞（不依赖点击 UI）。 */
    void sendLike(Object feed, String key, ClassLoader cl) {
        try {
            Class<?> svcCls = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService", false, cl);
            Method v0 = svcCls.getDeclaredMethod("v0");
            v0.setAccessible(true);
            Object svc = v0.invoke(null);

            Class<?> mCls = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService$m", false, cl);
            Object m = mCls.getDeclaredConstructor().newInstance();

            Object comm = callMethod(feed, "getFeedCommInfo");
            Object opInfo = callMethod(feed, "getOperationInfo");

            set(m, "a", get(comm, "ugckey"));        // ugckey
            set(m, "b", get(comm, "curlikekey"));    // unikey
            set(m, "c", get(comm, "orglikekey"));    // orglikekey
            set(m, "e", Boolean.TRUE);               // true = 点赞
            set(m, "f", intOf(get(comm, "appid")));
            set(m, "g", opInfo == null ? null : get(opInfo, "busiParam"));
            set(m, "i", -1);
            set(m, "p", 1);
            set(m, "u", feed);
            set(m, "v", intOf(get(feed, "feedType")));

            Method u1 = svcCls.getDeclaredMethod("u1", Handler.class, mCls);
            u1.setAccessible(true);
            u1.invoke(svc, null, m);
            fileLog("auto-like sent: " + key);
            log(4, TAG, "auto-like sent: " + key);
        } catch (Throwable t) {
            fileLog("sendLike failed: " + t);
            log(5, TAG, "sendLike failed: " + t);
            synchronized (DONE) {
                DONE.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static Object callMethod(Object obj, String name, Object... args) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (m.getName().equals(name)
                        && m.getParameterTypes().length == args.length) {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    static Object get(Object obj, String field) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    static void set(Object obj, String field, Object value) {
        if (obj == null) return;
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    static int intOf(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }
}