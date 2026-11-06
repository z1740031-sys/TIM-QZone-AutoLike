package com.tim.qzone.autolike;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
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

    /** 新架构：列表项绑定数据时触发（“刷到”）。 */
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
                        onFeedShown(chain.getArg(0), cl);
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

    /** 旧架构：列表 getView（“刷到”）。 */
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
                        if (data != null) onFeedShown(data, cl);
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
    // 自动点赞逻辑
    // ------------------------------------------------------------------

    static void onFeedShown(Object feed, ClassLoader cl) {
        if (feed == null || !enabled()) return;
        try {
            Object comm = callMethod(feed, "getFeedCommInfo");
            final String key = comm == null ? null : (String) get(comm, "curlikekey");
            if (TextUtils.isEmpty(key)) return;

            // 去重最优先：已处理过的说说直接返回，避免重复回调刷屏
            synchronized (DONE) {
                if (DONE.contains(key)) return;
            }

            boolean firstLog;
            synchronized (SEEN) {
                firstLog = SEEN.add(key);
            }

            long uin = 0L;
            try {
                Object u = callMethod(feed, "getUser");
                uin = u == null ? 0L : ((Number) get(u, "uin")).longValue();
            } catch (Throwable ignore) {
            }

            // 仅好友过滤
            if (friendsOnly()) {
                Boolean isFrd = isQQFriend(uin, cl);
                if (isFrd == null) {
                    // 拿不到好友关系时退回启发式，保证功能可用
                    boolean heur = isFriendFeed(feed);
                    if (firstLog) {
                        fileLog("friend unknown, heuristic=" + heur
                                + " uin=" + uin + " key=" + key);
                    }
                    if (!heur) {
                        synchronized (DONE) {
                            DONE.add(key);
                        }
                        return;
                    }
                } else if (!isFrd.booleanValue()) {
                    if (firstLog) fileLog("skip non-friend uin=" + uin + " key=" + key);
                    synchronized (DONE) {
                        DONE.add(key);
                    }
                    return;
                }
            }

            Object likeInfo = callMethod(feed, "getLikeInfo");
            if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isLiked"))) {
                if (firstLog) fileLog("skip already liked key=" + key);
                synchronized (DONE) {
                    DONE.add(key);
                }
                return;
            }
            Object localInfo = callMethod(feed, "getLocalInfo");
            if (localInfo != null && Boolean.FALSE.equals(get(localInfo, "canLike"))) {
                return; // 不可赞（可能是数据未就绪，不记入 DONE，稍后回调可重试）
            }
            if (likeInfo != null && Boolean.TRUE.equals(get(likeInfo, "isFakeOperation"))) {
                return; // 假数据（本地乐观态，不记入 DONE）
            }

            synchronized (DONE) {
                if (DONE.contains(key)) return;
                DONE.add(key);
            }
            if (firstLog) fileLog("like queued uin=" + uin + " key=" + key);

            final Object f = feed;
            final ClassLoader loader = cl;
            final MainHook self = sInstance;
            UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (self != null) self.sendLike(f, key, loader);
                }
            }, Math.max(0, delayMs()));
        } catch (Throwable t) {
            if (sInstance != null) sInstance.log(5, TAG, "onFeedShown failed: " + t);
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