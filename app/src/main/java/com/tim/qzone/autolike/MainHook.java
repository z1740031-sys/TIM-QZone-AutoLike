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

    /** 观察点赞请求，记录已赞，便于去重。
     *
     * ⚠️ 实测签名（TIM 4.1.0，运行时打印确认）：
     *      QZoneWriteOperationService.u1(Handler, QZoneWriteOperationService$m) -> void
     *   内部构造 QZoneLikeRequest（点赞）；f0(...) 构造 QzoneDissRequest（取消赞）。
     *   这里遍历所有 u1 重载，挑出「参数里含 QZoneWriteOperationService$m」的那个，
     *   并记录 m 是第几个参数，避免签名变动导致失效。 */
    void hookLikeSender(final ClassLoader cl) {
        try {
            Class<?> svc = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService", false, cl);
            Class<?> mCls = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService$m", false, cl);

            Method picked = null;
            int mArg = -1;
            StringBuilder cand = new StringBuilder();
            for (Method mm : svc.getDeclaredMethods()) {
                if (!"u1".equals(mm.getName())) continue;
                Class<?>[] ps = mm.getParameterTypes();
                cand.append("u1(");
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) cand.append(',');
                    cand.append(ps[i].getSimpleName());
                }
                cand.append(") ");
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == mCls) {
                        picked = mm;
                        mArg = i;
                        break;
                    }
                }
                if (picked != null) break;
            }
            fileLog("u1 候选: " + cand);
            if (picked == null) {
                fileLog("hook likeSender SKIP: 未找到含 m 参数的 u1");
                log(5, TAG, "hook likeSender skip: no u1(...m...)");
                return;
            }
            final int argIdx = mArg;
            final Method target = picked;
            hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object mv = chain.getArg(argIdx);
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
            fileLog("hooked u1 " + target + "  m@arg" + argIdx);
            log(4, TAG, "hooked u1 " + target);
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

        /** 重试计数（canLike / 好友关系未就绪时稍后重试，而非直接放弃） */
        int retry;
        /** 页面判定缓存：0=未知 1=允许 -1=禁止 */
        int pageState;
        /** 上次页面判定时间（用于对「未知」做限频重算，避免轮询里反复反射） */
        long pageCheckedAt;

        /**
         * ⭐ 绑定时刻的请求参数快照。
         *
         * 为什么必须在绑定时取？
         *   RecyclerView 会回收复用 View 与 feed 对象。而点赞是「看到后 300ms」才发出，
         *   这期间若用户滚动导致复用，发送时再读 getFeedCommInfo() 可能拿到
         *   **另一条说说** 的 curlikekey —— 服务端会拒绝，表现为随机漏赞。
         */
        String ugckey;
        String curlikekey;
        String orglikekey;
        int appid;
        int feedType;
        boolean todayInHistory;
        Object busiParam;

        /** 已重发点赞次数（用于「未确认」时的补偿重试） */
        int likeRetry;
        /** 本轮确认已等待的次数（每 200ms 一次） */
        int verifyTries;

        Candidate(View v, Object feed, String key, long uin) {
            this.viewRef = new WeakReference<View>(v);
            this.feed = feed;
            this.key = key;
            this.uin = uin;
            this.createdAt = System.currentTimeMillis();
            snapshot(feed, key);
        }

        /** 把点赞所需参数一次性抓下来（后续发送只用这份快照）。 */
        void snapshot(Object f, String k) {
            try {
                this.curlikekey = k;
                Object comm = callMethodQuiet(f, "getFeedCommInfo");
                if (comm != null) {
                    Object v = get(comm, "ugckey");
                    this.ugckey = v == null ? null : String.valueOf(v);
                    Object o = get(comm, "orglikekey");
                    this.orglikekey = o == null ? null : String.valueOf(o);
                    this.appid = intOf(get(comm, "appid"));
                    try {
                        this.todayInHistory =
                                Boolean.TRUE.equals(callMethod(comm, "isTodayInHistoryFeed"));
                    } catch (Throwable ignore) {
                    }
                }
                Object op = callMethodQuiet(f, "getOperationInfo");
                this.busiParam = op == null ? null : get(op, "busiParam");
                this.feedType = intOf(get(f, "feedType"));
            } catch (Throwable ignore) {
            }
        }
    }

    /** 候选池：key -> Candidate（弱引用 View，随滚动自动失效） */
    static final java.util.LinkedHashMap<String, Candidate> PENDING =
            new java.util.LinkedHashMap<String, Candidate>();

    /** 轮询是否已启动 */
    static boolean sPolling;

    /** 保护 sPolling 与 PENDING 的联合状态，避免轮询停摆竞态。 */
    static final Object POLL_LOCK = new Object();

    static final long POLL_INTERVAL_MS = 60L;

    /**
     * 「刷到」判定：View 是否已经出现在屏幕上（含点赞按钮所在区域）。
     *
     * 用 getGlobalVisibleRect 拿到真正可见的矩形；只要该列表项
     * **有可见部分**（露出高度达到阈值）就认为「刷到了」。
     */
    static boolean isViewVisible(View v) {
        if (v == null) return false;
        if (!v.isShown()) {
            diagVisible(v, "notShown");
            return false;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            diagVisible(v, "zeroSize");
            return false;
        }

        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r)) {
            diagVisible(v, "offScreen");
            return false;                               // 完全在屏幕外
        }
        if (r.height() <= 0) {
            diagVisible(v, "rectEmpty");
            return false;
        }

        float ratio = r.height() / (float) v.getHeight();
        if (ratio >= visibleRatio()) return true;

        // ⭐ 长说说（图文/多图）即使只露出很少一部分，点赞按钮也可能已经在屏幕上。
        //    这里补一条兜底：只要**露出高度超过 80px** 也算「刷到」。
        if (r.height() >= MIN_VISIBLE_PX) return true;

        diagVisible(v, "ratio=" + String.format("%.2f", ratio) + " px=" + r.height());
        return false;
    }

    /** 可见性诊断计数（只记前若干条，避免日志爆炸）。 */
    static int sVisDiagCount;

    /** 记录一次「判定为不可见」的详情，用于定位漏赞。 */
    static void diagVisible(View v, String why) {
        try {
            if (sVisDiagCount >= 30) return;
            sVisDiagCount++;
            Rect r = new Rect();
            boolean vis = v.getGlobalVisibleRect(r);
            fileLog("vis-diag[" + sVisDiagCount + "] " + why
                    + " cls=" + v.getClass().getSimpleName()
                    + " w=" + v.getWidth() + " h=" + v.getHeight()
                    + " shown=" + v.isShown()
                    + " rectVis=" + vis + " rect=" + r.toShortString()
                    + " parentH=" + (v.getParent() instanceof View
                        ? ((View) v.getParent()).getHeight() : -1));
        } catch (Throwable ignore) {
        }
    }

    /** 兜底阈值：露出高度（px）超过该值即视为已刷到。 */
    static final int MIN_VISIBLE_PX = 80;

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

    /** 允许点赞的页面类名（好友动态）。
     *
     * ⚠️ 实测（日志 page=...）：
     *   com.qzone.reborn.base.QZoneShellActivity              ← 真正承载动态列表的容器 Activity
     *   com.qzone.reborn.feedx.activity.QZoneFriendFeedXActivity
     * 两者都属「好友动态」链路，必须放行。 */
    /**
     * 页面关键字判定（v2.1 起用 contains 匹配，比精确类名更抗混淆/版本变化）。
     *
     * 判定顺序：先 BLOCK 再 ALLOW，最后「有 Fragment 但未列入」按允许处理。
     * 若日志出现 `ALLOW(unknown) xxx` 且该页不该赞，把它的关键字加进 BLOCK 即可。
     */
    /** 禁止：个人主页 / DIY 主页 / 名人主页 / 说说详情 / 单条说说列表 / AIO */
    static final String[] PAGE_BLOCK_KEYS = new String[]{
            "FeedxUserHomeFragment",
            "DIYUserHome",
            "FamousUserHome",
            "FeedXDetailFragment",
            "FeedXMoodListFragment",
            "AIOUserFeedxFragment",
            "UserHome",
    };
    /** 允许：好友动态（列表 Fragment 或其容器 Activity） */
    static final String[] PAGE_ALLOW_KEYS = new String[]{
            "QZoneFriendFeedXFragment",
            "QZoneFriendFeedXActivity",
    };

    /** 明确排除的页面（个人主页 / DIY / 名人主页 / 详情页等）。 */
    

    /**
     * 页面判定（⭐ v2.1 延迟判定版）。
     *
     * 返回：1=允许  0=未知（尚未 attach，稍后重试）  -1=禁止
     *
     * ⚠️ 为什么不能在 onFeedBound 里判？
     *   进入空间瞬间 TIM 会预绑定首屏 itemView，此时承载它的 Fragment
     *   还没 attach，反查只能拿到「壳 Activity」QZoneShellActivity。
     *   早期版本遇到壳就直接拒绝，导致首屏全部错过，必须滚动（触发
     *   itemView 复用重新绑定，此时 Fragment 已 attach）才会补赞 ——
     *   表现为「进入不点赞，翻走再翻回才显示」。
     *   因此在**真正可见的那一刻**再判，判定就准确了。
     */
    static int pageVerdict(View v) {
        try {
            java.util.List<Object> owners = findAncestorFragments(v);
            // ① 禁止关键字优先：父链上只要出现「个人主页」类 Fragment 就一律禁止
            for (int i = 0; i < owners.size(); i++) {
                String cls = owners.get(i).getClass().getName();
                for (String b : PAGE_BLOCK_KEYS) {
                    if (cls.contains(b)) {
                        pageLog("BLOCKED " + cls, -1);
                        return -1;
                    }
                }
            }
            // ② 允许关键字：父链上有「好友动态」Fragment → 允许
            for (int i = 0; i < owners.size(); i++) {
                String cls = owners.get(i).getClass().getName();
                for (String a : PAGE_ALLOW_KEYS) {
                    if (cls.contains(a)) return 1;
                }
            }
            // ③ 有 Fragment 但都未列入：记录类名，按「允许」处理以便观察；
            //    若后续发现某页面被误赞，把它的关键字补进 PAGE_BLOCK_KEYS 即可。
            if (!owners.isEmpty()) {
                pageLog("ALLOW(unknown) " + owners.get(0).getClass().getName(), 1);
                return 1;
            }
            // ④ 一个 Fragment 都没拿到：可能尚未 attach，降级看宿主 Activity
            Object act = findHostActivity(v);
            if (act != null) {
                String an = act.getClass().getName();
                if (an.contains("QZoneFriendFeedXActivity")) return 1;
                if (an.contains("QZoneShellActivity")) return 0;   // 壳：等 attach
                pageLog("UNKNOWN(activity) " + an, 0);
                return 0;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 候选级页面判定缓存：已判定的直接复用，未判定的最多每 400ms 重算一次。 */
    static int pageVerdictCached(Candidate c, View v) {
        if (c.pageState == 1 || c.pageState == -1) return c.pageState;

        // ⭐ v2.5：全局页面判定缓存。
        //
        // 性能问题：pageVerdict → findAncestorFragments 里多次调用 invokeAny，
        //   而 invokeAny 每次都遍历 getDeclaredMethods()（几百次反射查找）。
        //   60ms 轮询 × 每个候选都跑一遍 → UI 线程严重卡顿，表现为「点赞很慢」。
        // 对策：同一页面内判定结果不变，加带 TTL 的全局缓存，
        //   一个轮询周期内所有候选共享一次判定。
        // 缓存键必须带上「所属列表」的身份：TIM 用同一个 QZoneShellActivity 承载
        // 好友动态页与个人主页（禁止页）。若只按时间缓存，切页后 300ms 内会把
        // 个人主页误判为「允许」→ 给陌生人的说说点赞。用最近的 RecyclerView
        // 实例区分：不同页面的列表是不同的实例，绝不会串。
        final int listKey = listKeyOf(v);
        long t = System.currentTimeMillis();
        if (sGPageVerdict != -99 && sGListKey == listKey && t - sGPageAt < GLOBAL_PAGE_TTL) {
            c.pageState = sGPageVerdict;
            c.pageCheckedAt = t;
            return sGPageVerdict;
        }

        long now = System.currentTimeMillis();
        if (c.pageState == 0 && now - c.pageCheckedAt < 150L) return 0;
        int r = pageVerdict(v);
        c.pageState = r;
        c.pageCheckedAt = now;
        // 只缓存「确定结果」，避免把一次偶然的未知长期固化
        if (r == 1 || r == -1) {
            sGPageVerdict = r;
            sGPageAt = now;
            sGListKey = listKey;
        }
        return r;
    }
    /**
     * 取出 itemView 所属列表（RecyclerView）的身份值，用作页面判定缓存的键。
     *
     * 为什么需要：TIM 用一个 QZoneShellActivity 承载「好友动态」与「个人主页」，
     * 只按时间做缓存的话，切页瞬间可能把禁止页当成允许页，从而给陌生人的
     * 说说点赞。不同页面的列表是不同的 View 实例，用 identityHashCode 即可区分。
     * 找不到（例如非列表场景）返回 0，退化为不带身份判定。
     */
    static int listKeyOf(View v) {
        try {
            View cur = v;
            int guard = 0;
            while (cur != null && guard++ < 24) {
                String n = cur.getClass().getName();
                if (n.endsWith("RecyclerView")) return System.identityHashCode(cur);
                android.view.ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /** 全局页面判定结果（-99 = 无缓存）与时间戳。 */
    static int sGPageVerdict = -99;
    static long sGPageAt;
    /** 上次全局判定所属列表的身份（RecyclerView identityHashCode），防止切页串用 */
    static int sGListKey = 0;
    /** 全局判定缓存有效期（毫秒）。太长会在页面切换后误判，300ms 足够覆盖一轮轮询。 */
    static final long GLOBAL_PAGE_TTL = 300L;

    /** 页面判定日志（限流）。 */
    static int sPageLogCount;
    static int sLastVerdict = -99;

    static void pageLog(String detail, int verdict) {
        try {
            if (sPageLogCount >= 30) return;
            if (verdict == sLastVerdict) return;      // 连续相同不再刷
            sLastVerdict = verdict;
            sPageLogCount++;
            fileLog("page[" + sPageLogCount + "] verdict=" + verdict + " " + detail);
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
    /**
     * 收集 v 父链上所有的 Fragment（由近及远）。
     *
     * ⚠️ 旧实现用 `fragment.getView() == 走到顶层的 root`，
     *    但 getView() 通常是**中间层**容器而非 DecorView，等式几乎永不成立
     *    —— 于是永远匹配不到 Fragment，页面判定形同虚设。
     *    正确做法：从 itemView 逐级上溯，看哪一级正好是某 Fragment 的 getView()。
     */
    static java.util.List<Object> findAncestorFragments(View v) {
        java.util.List<Object> out = new java.util.ArrayList<Object>();
        try {
            Object act = findHostActivity(v);
            if (act == null) return out;
            Object fm = invokeAny(act, "getSupportFragmentManager");
            if (fm == null) fm = invokeAny(act, "getFragmentManager");
            if (fm == null) return out;
            Object frags = invokeAny(fm, "getFragments");
            if (!(frags instanceof java.util.List)) return out;
            java.util.List<?> list = (java.util.List<?>) frags;
            View cur = v;
            int guard = 0;
            while (cur != null && guard++ < 60) {
                for (int i = 0; i < list.size(); i++) {
                    Object f = list.get(i);
                    if (f == null) continue;
                    Object fv = invokeAny(f, "getView");
                    if (fv == cur) out.add(f);
                }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignore) {
        }
        return out;
    }



    /** 反射调用无参方法，沿父类查找；失败返回 null。 */
    /** 反射方法缓存：Class|方法名|参数个数 -> Method（null 表示已确认不存在） */
    static final java.util.HashMap<String, Method> METHOD_CACHE =
            new java.util.HashMap<String, Method>();

    static Method findMethod(Class<?> c0, String name, int argc) {
        String key = c0.getName() + '#' + name + '/' + argc;
        synchronized (METHOD_CACHE) {
            if (METHOD_CACHE.containsKey(key)) return METHOD_CACHE.get(key);
        }
        Method found = null;
        Class<?> c = c0;
        while (c != null) {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (m.getName().equals(name) && m.getParameterTypes().length == argc) {
                    m.setAccessible(true);
                    found = m;
                    break;
                }
            }
            if (found != null) break;
            c = c.getSuperclass();
        }
        synchronized (METHOD_CACHE) {
            METHOD_CACHE.put(key, found);
        }
        return found;
    }

    static Object invokeAny(Object obj, String name) {
        if (obj == null) return null;
        try {
            Method m = findMethod(obj.getClass(), name, 0);
            if (m != null) return m.invoke(obj);
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
            // ⭐ 页面过滤（v2.1）：
            //   绑定阶段 Fragment 可能还没 attach（只能拿到壳 Activity），
            //   此时**不**下结论；等轮询到「真正可见」时再判（见 pollOnce）。
            //   只有「明确禁止」的页面才在这里立刻跳过。
            int verdict = pageVerdict(itemView);
            if (verdict < 0) {
                return;                                  // 明确的禁止页（个人主页等）
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
        synchronized (POLL_LOCK) {
            if (sPolling) return;
            sPolling = true;
        }
        UI.post(new Runnable() {
            @Override
            public void run() {
                boolean empty;
                try {
                    pollOnce(cl);
                } catch (Throwable ignore) {
                }
                synchronized (PENDING) {
                    empty = PENDING.isEmpty();
                }
                if (empty) {
                    // ⚠️ 必须在锁内复位，否则与 onFeedBound 的「检查 sPolling + 入池」
                    //    形成竞态：轮询已判定为空但还没复位时，新候选入池，
                    //    随后 sPolling 被置 false，新候选永远等不到轮询 → 表现为「完全不点赞」。
                    synchronized (POLL_LOCK) {
                        synchronized (PENDING) {
                            if (PENDING.isEmpty()) {
                                sPolling = false;
                                return;
                            }
                        }
                        // 期间又有新候选进来 → 继续轮询
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
                    dr("viewRecycled", c);
                    it.remove();                    // View 已被回收
                    continue;
                }
                if (now - c.createdAt > 30000L) {
                    dr("timeout30s", c);
                    it.remove();                    // 30s 都没刷到，丢弃
                    continue;
                }
                if (isViewVisible(v)) {
                    // ⭐ 页面判定（v2.1）：必须放在「真正可见」之后。
                    //   绑定阶段 Fragment 可能还没 attach（只得壳 Activity），
                    //   此刻再判才准确；verdict==0 表示仍未就绪，保留重试。
                    //   用候选级缓存，避免 60ms 轮询里反复做反射。
                    int verdict = pageVerdictCached(c, v);
                    if (verdict == 0) {
                        continue;               // 尚未 attach，下一轮再判
                    }
                    if (verdict < 0) {
                        dr("pageBlocked", c);
                        it.remove();            // 明确的禁止页（个人主页等）→ 出池
                        continue;
                    }
                    // ⚠️ 先做前置校验：若数据还没加载完（canLike/好友关系未就绪），
                    //    **留在池中**等下一轮重试，而不是出池丢弃 —— 否则该条永远不赞。
                    if (!precheck(c, cl)) {
                        if (c.retry > 0 && c.retry < MAX_RETRY) {
                            continue;               // 保留，下一轮再试
                        }
                        it.remove();                // 已赞 / 非好友 / 重试超限 → 出池
                        continue;
                    }
                    it.remove();                    // 刷到了且可赞 → 出池
                    ready.add(c);
                }
            }
        }

        for (int i = 0; i < ready.size(); i++) {
            sendQueued(ready.get(i), cl);
        }
    }

    /**
     * 排入点赞队列：**延迟 likeDelayMs()（默认 300ms）后发送**。
     *
     * ⚠️ 前置校验（好友/已赞/可赞）已在 pollOnce 中完成，这里不再重复，
     *    以免数据在 300ms 内变化导致误判丢弃。
     * ⚠️ 排入后不再关心该项是否还在屏幕上 —— 即使这 300ms 内
     *    用户划到了更下面的内容，点赞依然会照常发出。
     */
    static void sendQueued(final Candidate c, final ClassLoader cl) {
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
        final View view = c.viewRef.get();          // ⭐ 保留 View 用于点赞后刷新图标
        final MainHook self = sInstance;
        final Candidate cand = c;
        UI.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 到点就发，不检查是否还在屏幕内
                if (self != null) self.sendLike(cand, cl, view);
            }
        }, likeDelayMs());
    }

    /** 点赞前校验（好友 / 已赞 / 可赞 / 假数据）。返回 true 表示可以点赞。 */
    static boolean precheck(Candidate c, ClassLoader cl) {
        // 仅好友过滤
        if (friendsOnly()) {
            Boolean isFrd = isQQFriend(c.uin, cl);
            if (isFrd == null) {
                if (!isFriendFeed(c.feed)) {
                    reason(c, "notFriendFeed");
                    return false;
                }
            } else if (!isFrd.booleanValue()) {
                // ⚠️ FriendsManager 可能在冷启动时尚未就绪，会误报「非好友」。
                //    这里同样允许重试，超过上限才真正放弃。
                if (c.retry < MAX_RETRY) {
                    c.retry++;
                    reason(c, "notFriend(retry " + c.retry + ")");
                    return false;
                }
                reason(c, "notFriend(giveup)");
                return false;
            }
        }
        Object likeInfo = callMethodQuiet(c.feed, "getLikeInfo");
        if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isLiked"))) {
            reason(c, "alreadyLiked");
            return false;                                   // 已赞
        }
        // ⚠️ canLike == false 也有两种情况：数据未加载完（重试）或确实不可赞（放弃）
        Object localInfo = callMethodQuiet(c.feed, "getLocalInfo");
        if (localInfo != null && Boolean.FALSE.equals(get(localInfo, "canLike"))) {
            if (c.retry < MAX_RETRY) {
                c.retry++;
                reason(c, "canLike=false(retry " + c.retry + ")");
                return false;                               // 本次不赞，但留在候选池
            }
            reason(c, "canLike=false(giveup)");
            return false;
        }
        if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isFakeOperation"))) {
            reason(c, "fakeOperation");
            return false;                                   // 本地假数据
        }
        return true;
    }

    /** 统一的「precheck 未通过」诊断（限流，避免日志爆炸）。 */
    static int sReasonCount;

    static void reason(Candidate c, String why) {
        try {
            if (sReasonCount >= 60) return;
            sReasonCount++;
            fileLog("precheck[" + sReasonCount + "] " + why + " uin=" + c.uin);
        } catch (Throwable ignore) {
        }
    }

    /** 「候选被移出池」的诊断（限流）。 */
    static int sDrCount;

    static void dr(String why, Candidate c) {
        try {
            if (sDrCount >= 60) return;
            sDrCount++;
            fileLog("drop[" + sDrCount + "] " + why + " uin=" + c.uin
                    + " retry=" + c.retry);
        } catch (Throwable ignore) {
        }
    }

    /** 统一重试上限（canLike / 好友关系未就绪时）。 */
    static final int MAX_RETRY = 15;

    static Object callMethodQuiet(Object obj, String name, Object... args) {
        try {
            return callMethod(obj, name, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射构造 QZoneWriteOperationService$m 并调用 u1 发送点赞（不依赖点击 UI）。
     *
     * 字段赋值严格对齐 TIM 官方实现
     * （com.qzone.common.logic.clickprocessor.BaseFeedClickProcessor#H1VLL 的字节码）：
     *
     *   m.a = feed.getFeedCommInfo().ugckey
     *   m.b = feed.getFeedCommInfo().curlikekey
     *   m.c = feed.getFeedCommInfo().orglikekey
     *   m.e = true                                  // 点赞
     *   m.f = feed.getFeedCommInfo().appid
     *   m.g = feed.getOperationInfo().busiParam
     *   m.i = -1
     *   m.k = feed.getFeedCommInfo().isTodayInHistoryFeed()   ← 之前漏了
     *   m.l = QZoneBaseFeedFragment.F7(...)                   ← 之前漏了（页面来源）
     *   m.u = feed
     *   m.v = feed.feedType
     *   m.p 官方**不设置**（之前误设 1）
     *
     * 发送：QZoneWriteOperationService.v0().u1(m, boolean) -> void
     *      （内部构造 QZoneLikeRequest；而 f0(...) 是 QzoneDissRequest = 取消赞）
     */
    void sendLike(Candidate c, ClassLoader cl, View itemView) {
        Object feed = c.feed;
        String key = c.key;
        try {
            Class<?> svcCls = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService", false, cl);
            Method v0 = svcCls.getDeclaredMethod("v0");
            v0.setAccessible(true);
            Object svc = v0.invoke(null);

            Class<?> mCls = Class.forName(
                    "com.qzone.common.business.service.QZoneWriteOperationService$m", false, cl);
            Object m = mCls.getDeclaredConstructor().newInstance();

            // ⭐ 全部使用**绑定时刻的快照**，避免 View 复用导致拿到别的说说的 key
            set(m, "a", c.ugckey);                   // ugckey
            set(m, "b", c.curlikekey);               // unikey
            set(m, "c", c.orglikekey);               // orglikekey
            set(m, "e", Boolean.TRUE);               // true = 点赞
            set(m, "f", Integer.valueOf(c.appid));
            set(m, "g", c.busiParam);
            set(m, "i", -1);
            set(m, "k", Boolean.valueOf(c.todayInHistory));
            set(m, "l", pageSource(feed));
            set(m, "u", feed);
            set(m, "v", Integer.valueOf(c.feedType));

            // 动态查找 u1(..., m, ...)：实测签名为 u1(Handler, QZoneWriteOperationService$m)
            // 不硬编码参数类型，按「参数中含 m」来定位，并记录 m 的位置
            Method u1 = null;
            int mPos = -1;
            StringBuilder cand = new StringBuilder();
            for (Method mm : svcCls.getDeclaredMethods()) {
                if (!"u1".equals(mm.getName())) continue;
                Class<?>[] ps = mm.getParameterTypes();
                cand.append("u1(");
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) cand.append(',');
                    cand.append(ps[i].getSimpleName());
                }
                cand.append(") ");
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == mCls) {
                        u1 = mm;
                        mPos = i;
                        break;
                    }
                }
                if (u1 != null) break;
            }
            if (u1 == null) {
                fileLog("sendLike: 未找到含 m 的 u1  候选=" + cand);
                throw new NoSuchMethodException("u1(...m...) not found: " + cand);
            }
            u1.setAccessible(true);
            // 按真实参数表填充：m 放对应位置，其余用 null / false / 0
            Class<?>[] pt = u1.getParameterTypes();
            Object[] argv = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                if (i == mPos) {
                    argv[i] = m;
                } else if (pt[i] == boolean.class) {
                    argv[i] = Boolean.FALSE;
                } else if (pt[i] == int.class) {
                    argv[i] = Integer.valueOf(0);
                } else if (pt[i] == long.class) {
                    argv[i] = Long.valueOf(0L);
                } else {
                    argv[i] = null;   // Handler 传 null 与官方一致
                }
            }
            u1.invoke(svc, argv);
            fileLog("auto-like sent: " + key + "  via " + u1);
            log(4, TAG, "auto-like sent: " + key);

            // ⭐⭐ v2.5：发出后**快速轮询确认**，一确认立刻点亮红心。
            //
            // 旧版：固定等 1500ms 才查一次 → 红心要 300+1500 ≈ 1.8s 才亮，
            //       主观上就是「点赞很慢」。
            // 新版：每 200ms 查一次（最多 ~4s），服务端一回来就立刻刷新图标，
            //       通常 400~600ms 就能看到红心；同时保留「未确认则重发」的兜底。
            final Candidate cc = c;
            final View vv = itemView;
            final ClassLoader cll = cl;
            UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    verifyLiked(cc, cll, vv);
                }
            }, 200L);
        } catch (Throwable t) {
            fileLog("sendLike failed: " + t);
            log(5, TAG, "sendLike failed: " + t);
            synchronized (DONE) {
                DONE.remove(key);
            }
        }
    }

    /**
     * 点赞结果回查：读本地 likeInfo.isLiked 判断是否真的赞上。
     * 未成功 → 用同一份参数快照重发（最多 2 次）。
     */
    void verifyLiked(final Candidate c, final ClassLoader cl, final View itemView) {
        try {
            Object likeInfo = callMethodQuiet(c.feed, "getLikeInfo");
            if (likeInfo == null) {
                // 拿不到 likeInfo（反射不到）→ 无法判定，按成功处理并刷新图标，
                // 避免误判为失败而重复发送。
                fileLog("like unverifiable (no likeInfo), assume ok uin=" + c.uin);
                applyLikedLocally(c.feed, itemView);
                return;
            }
            boolean liked = Boolean.TRUE.equals(get(likeInfo, "isLiked"));
            if (liked) {
                fileLog("like confirmed: uin=" + c.uin);
                applyLikedLocally(c.feed, itemView);
                return;
            }
            // ⭐ 未确认：先继续等（服务端可能还在路上），攒够次数才重发。
            //    旧版是「一发现未确认立刻重发」，会在服务端正常但稍慢时误重发。
            c.verifyTries++;
            if (c.verifyTries < VERIFY_WAIT_TRIES) {
                UI.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        verifyLiked(c, cl, itemView);
                    }
                }, 200L);
                return;
            }
            // 等够了还没确认 → 判定失败，重发
            if (c.likeRetry < 2) {
                c.likeRetry++;
                c.verifyTries = 0;
                fileLog("like NOT confirmed, retry " + c.likeRetry + " uin=" + c.uin);
                sendLike(c, cl, itemView);
                return;
            }
            fileLog("like give up after retries: uin=" + c.uin + " key=" + c.key);
        } catch (Throwable t) {
            fileLog("verifyLiked failed: " + t);
        }
    }

    /** 确认前先等多少轮（每轮 200ms）。1000ms 内服务端通常都会回。 */
    static final int VERIFY_WAIT_TRIES = 5;

    /**
     * 把「已赞」写回本地模型并刷新图标。
     *
     * 依次尝试（任一成功即可，失败不影响已发出的点赞请求）：
     *   ① feed.getLikeInfo().isLiked = true，并 likeNum + 1
     *   ② itemView 重新走一遍绑定（借助 adapter.notifyItemChanged / 手工 invalidate）
     */
    static void applyLikedLocally(Object feed, View itemView) {
        try {
            // ① 更新数据模型
            Object likeInfo = callMethodQuiet(feed, "getLikeInfo");
            if (likeInfo != null) {
                Boolean liked = (Boolean) get(likeInfo, "isLiked");
                if (liked == null || !liked.booleanValue()) {
                    set(likeInfo, "isLiked", Boolean.TRUE);
                    // 点赞数 +1（字段名在不同版本可能是 likeNum / iLikeNum，逐个尝试）
                    bumpLikeCount(likeInfo);
                    fileLog("local model marked liked");
                }
            }
            // ② 刷新界面
            refreshItemView(itemView);
        } catch (Throwable t) {
            fileLog("applyLikedLocally failed: " + t);
        }
    }

    /** 点赞数自增（字段名兼容多版本）。 */
    static void bumpLikeCount(Object likeInfo) {
        String[] names = new String[]{"likeNum", "iLikeNum", "totalLikeNum"};
        for (int i = 0; i < names.length; i++) {
            Object v = get(likeInfo, names[i]);
            if (v instanceof Number) {
                set(likeInfo, names[i], Integer.valueOf(((Number) v).intValue() + 1));
                return;
            }
        }
    }

    /**
     * 让 itemView 重新渲染：优先通知 RecyclerView 重绑该项，其次手动 invalidate。
     * 全部走反射（工程无 androidx 依赖）。
     */
    static void refreshItemView(View itemView) {
        if (itemView == null) return;
        try {
            // 找 RecyclerView 祖先
            View parent = itemView;
            Object rv = null;
            int guard = 0;
            while (parent != null && guard++ < 40) {
                String n = parent.getClass().getName();
                if (n.contains("RecyclerView")) {
                    rv = parent;
                    break;
                }
                parent = (parent.getParent() instanceof View)
                        ? (View) parent.getParent() : null;
            }
            if (rv != null) {
                // getChildAdapterPosition(itemView) → position
                Method mPos = null;
                try {
                    mPos = rv.getClass().getMethod("getChildAdapterPosition", View.class);
                } catch (Throwable ignore) {
                }
                if (mPos != null) {
                    mPos.setAccessible(true);
                    Object pos = mPos.invoke(rv, itemView);
                    if (pos instanceof Integer && ((Integer) pos).intValue() >= 0) {
                        Object adapter = callMethodQuiet(rv, "getAdapter");
                        if (adapter != null) {
                            Method notify = adapter.getClass()
                                    .getMethod("notifyItemChanged", int.class);
                            notify.setAccessible(true);
                            notify.invoke(adapter, pos);
                            fileLog("refresh: notifyItemChanged(" + pos + ")");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        // 兜底：让该 View 自行重绘
        try {
            itemView.invalidate();
            itemView.requestLayout();
        } catch (Throwable ignore) {
        }
    }

    /** 取页面来源标识（对应官方的 QZoneBaseFeedFragment.F7(...)）。
     *  取不到时返回 0（官方默认值），不影响点赞。 */
    static int pageSource(Object feed) {
        try {
            Object v = callMethod(feed, "getFeedCommInfo");
            Object src = get(v, "pageFrom");
            if (src instanceof Number) return ((Number) src).intValue();
        } catch (Throwable ignore) {
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static Object callMethod(Object obj, String name, Object... args) throws Exception {
        Method m = findMethod(obj.getClass(), name, args.length);
        if (m == null) throw new NoSuchMethodException(name);
        return m.invoke(obj, args);
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