# TIM 空间说说自动点赞 (TIM QZone AutoLike)

一个面向 **TIM（`com.tencent.tim`）** 的 LSPosed / Xposed 模块，
在浏览「好友动态 / 空间说说」时，**看到哪条说说的点赞按钮，就在约 300ms 后自动点赞**。

> 设计思路参考了 WeKit（微信模块）的「声明式功能 + 运行时解析宿主类」方案，
> 针对 TIM 的 QZone 点赞链路做了具体适配。

---

## 功能特性

| 功能 | 说明 |
|---|---|
| 自动点赞 | **看到说说真正出现在屏幕上**才触发，约 300ms 后自动点赞 |
| 划走照发 | 300ms 内即使已经划到更下面的内容，该条仍会完成点赞 |
| 仅赞「动态说说」 | 从好友资料页进入的「他的动态」等页面**不会**点赞 |
| 仅点赞好友 | 默认开启。通过 TIM 真实的 `FriendsManager` 判断好友关系，跳过推荐 / 名人 / 陌生人 |
| 智能去重 | 以 `curlikekey` 为唯一键，同一说说只赞一次 |
| 跳过干扰内容 | 自动排除广告、假数据（`isFakeOperation`）、不可赞的说说 |
| 可调项 | 设置界面可调「看到后等待时间」与「露出百分比阈值」 |
| 日志自管理 | 日志位于 TIM 私有目录，**满 200 行自动清空**，不会无限增长 |
| 设置界面 | 独立 App 图标：启用开关 / 仅好友开关 / 等待时间 / 露出比例 / 查看日志 |

---

## 工作原理（逆向分析结论）

### 1. 定位「刷到说说」的时机

TIM 的空间包含两套列表实现，模块同时 Hook 两者以保证覆盖：

| 架构 | 入口方法 |
|---|---|
| 新架构（Reborn / feedx） | `com.qzone.reborn.feedx.itemview.QZoneBaseFeedItemView#O(BusinessFeedData, int)` |
| 旧架构 | `com.qzone.common.activities.base.QZoneBaseFeedAdapter#getView(...)` |

⚠️ **这两个方法只是「列表项被绑定数据」，并不等于「用户看到了」。**
RecyclerView 会预绑定屏幕外 2~5 屏的内容，若直接在此点赞，就会出现
「还没刷到的说说也被赞了」的问题。

因此模块的流程是：

```
绑定数据 → 只登记为候选（不点赞）
   ↓
60ms 轮询：用 getGlobalVisibleRect() 判断该条是否**真正出现在屏幕上**
   ↓
命中 → 移出候选池 → 延迟约 300ms（可配置）→ 发送点赞
   ↓
（这 300ms 内即使划走了，也照常发出）
```

### 1.5 只赞「动态说说」，排除个人主页

从好友资料页点进「他的动态」时，列表**复用同一套** `QZoneBaseFeedItemView` 渲染，
因此必须额外做页面判定。实测的类结构：

```
com.qzone.reborn.base.QZoneBaseFeedxFragment            ← 共同基类
  ├─ QZoneFriendFeedXFragment            ✅ 好友动态（允许点赞）
  ├─ QZoneFeedxUserHomeFragment          ❌ 个人主页（跳过）
  ├─ QZoneFeedxDIYUserHomeFragment       ❌ 个人主页 DIY
  ├─ QZoneFeedxFamousUserHomeFragment    ❌ 名人主页
  ├─ QZoneFeedXDetailFragment            ❌ 说说详情页
  └─ QZoneAIOUserFeedxFragment           ❌ 聊天中打开的动态
```

做法是从 itemView 向上找到所属 Fragment（经 `FragmentManager` 反查），
**只有 `QZoneFriendFeedXFragment` 才放行**；判定不出页面时采取保守策略（不点赞）。

### 2. 判断是否该点赞

```
feed.getLikeInfo().isLiked        == false   →  尚未点赞
feed.getLocalInfo().canLike       == true    →  允许点赞
feed.getLikeInfo().isFakeOperation== false   →  非本地假数据
feed.getFeedCommInfo().curlikekey            →  说说唯一标识（去重键）
```

### 3. 发送点赞请求

不模拟点击，而是直接调用 QZone 的写操作服务：

```java
// com.qzone.common.business.service.QZoneWriteOperationService
QZoneWriteOperationService.v0().u1(handler, m);
```

其中 `m` 是内部类 `QZoneWriteOperationService$m`，逐字段填充：

| 字段 | 含义 |
|---|---|
| `a` | `ugckey` |
| `b` | `unikey`（即 `curlikekey`，同时作为去重键） |
| `c` | `orglikekey` |
| `e` | `true` 表示「点赞」 |
| `f` | `appid` |
| `g` | `busiParam`（来自 `feed.getOperationInfo()`） |
| `i` | `position`，取 `-1` |
| `p` | `clickScene`，取 `1` |
| `u` | `BusinessFeedData` 本体 |
| `v` | `feedType` |

### 4. 判断「是否好友」

坑点：`getManager(int)` **并不在** `BaseApplicationImpl` 上（后者只是 `Application` 的子类），
而在 `mqq.app.AppRuntime` 上。因此正确链路是：

```java
Object app = BaseApplicationImpl.getApplication();   // Application
Object rt  = app.getRuntime();                       // mqq.app.AppRuntime
int idx    = QQManagerFactory.FRIENDS_MANAGER;       // 反射读常量（值由 sManagerStart 顺序生成）
Object mgr = rt.getManager(idx);                     // FriendsManager
boolean isFriend = mgr.e0(String.valueOf(uin));      // e0(String) = isFriend，备选 g0(String)
```

若反射失败（例如 TIM 大版本改名），会**降级**为启发式判断
（排除 `isAdFeeds` / `isGDTAdvFeed` / `isRecommendFriendsFeed` /
`isMayKnowFriendFeed` / `isFamousSpaceUserFeed` / `isNeedAddFriendBtn`），
并遵守「拿不到好友关系时不误判为非好友」的保守策略。

---

## 兼容性

| 项 | 值 |
|---|---|
| TIM | 4.1.0（`versionCode 4050`）实测通过 |
| Android | 14（实测），理论 5.0+ |
| 框架 | LSPosed 2.2.1（API 102）+ Zygisk；需 KernelSU / Magisk |
| ABI | arm64-v8a（其它 ABI 亦可，模块内无 native 代码） |

> 模块依赖 TIM 4.1.0 中**未被混淆**的类名与方法名
> （`O` / `getView` / `u1` / `e0` / `getRuntime`）。
> TIM 升级大版本后如失效，可按上文「工作原理」重新定位并修改常量。

---

## 安装

1. 从 [Releases](../../releases) 下载最新 `*.apk`（或自行构建，见下）。
2. 安装到设备。
3. 打开 **LSPosed 管理器** → 模块 → 启用「TIM 空间自动点赞」。
4. 在「作用域」中勾选 **TIM（com.tencent.tim）**。
5. **强制停止并重新打开 TIM**（作用域变更必须重启目标应用才生效）。
6. 打开 TIM → 「动态 / 好友动态」刷一刷，即会自动点赞。

### 验证是否生效

- 打开模块自身的设置界面，点 **「查看点赞日志」**；
- 或查看文件：`/storage/emulated/0/Android/data/com.tencent.tim/DianZan/DianZan.log`
- LSPosed 日志中搜索 `QZoneAutoLike`，应能看到：
  ```
  hooked QZoneBaseFeedItemView.O
  hooked QZoneBaseFeedAdapter.getView
  hooked QZoneWriteOperationService.u1
  friendManager ok via #1 = com.tencent.mobileqq.app.FriendsManager
  auto-like sent: http://user.qzone.qq.com/xxx/mood/xxxx
  ```

---

## 构建

### 方式一：GitHub Actions（推荐，无需本地环境）

推送到 `main` 分支或手动触发 `Build APK` 工作流即可，
构建产物在 Actions 的 **Artifacts** 中下载；打 `v*` 标签会自动创建 Release 并附上 APK。

### 方式二：本地构建

需要 **JDK 17** 与 **Android SDK（platform 34）**：

```bash
git clone https://github.com/<你的用户名>/TIM-QZone-AutoLike.git
cd TIM-QZone-AutoLike
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 签名说明

默认使用 debug 签名，便于直接安装测试。
若需长期稳定签名（避免升级时签名冲突），请在 `app/build.gradle.kts` 中配置自己的 keystore，
**切勿把 keystore 与密码提交到仓库**（`.gitignore` 已排除 `*.jks` / `*.keystore`）。

---

## 数据目录

模块所有运行数据统一存放在 TIM 的应用私有目录下：

```
/storage/emulated/0/Android/data/com.tencent.tim/DianZan/
└── DianZan.log         # 运行日志，满 200 行自动清空
```

> 该目录属于 TIM 的私有目录，普通应用无法直接访问。
> 请使用支持 Shizuku / root 的文件管理器（如 MT 管理器）查看。

---

## 常见问题

**Q：装好后没有反应？**
A：确认三件事 —— ① 模块已启用；② 作用域勾选了 TIM；③ **重启过 TIM**（作用域变更后必须重启宿主）。

**Q：设置界面里看不到「模块设置」入口？**
A：本模块使用独立 App 图标（名为「TIM 空间自动点赞」），不注入 TIM 的设置页，请直接在桌面打开它。

**Q：日志显示 `skip non-friend`，但那是我的好友？**
A：说明 `FriendsManager.e0()` 与当前 TIM 版本语义不符。请把日志中的 uin 发到 Issues，会改用备选方法（`g0` 或 `FriendsInfoService`）。

**Q：为什么好友资料页里的「他的动态」不会点赞？**
A：这是刻意设计。个人主页复用同一套说说渲染组件，模块通过页面判定只放行
「好友动态」页（`QZoneFriendFeedXFragment`），避免在翻看某人主页时批量误赞。

**Q：点赞太快 / 太慢怎么调？**
A：打开模块设置，调整：
- **看到后的等待时间**（默认 300ms）—— 看到点赞按钮后等多久发出；
- **露出百分比**（默认 10%）—— 说说露出多少比例才算「看到」。

**Q：会不会点赞自己的说说？**
A：当前逻辑只过滤「非好友」，自己的说说按好友关系通常会被跳过；如需要，可增加 `isOwner` 判断。

**Q：怎么完全卸载？**
A：在 LSPosed 中停用模块 → 卸载 App → 删除数据目录 `.../com.tencent.tim/DianZan/`。

---

## 免责声明

本项目仅供**学习与研究** Xposed 模块开发、Android 逆向分析技术之用。
使用者应自行承担因使用本模块产生的一切后果，包括但不限于账号风险。
请勿将本模块用于任何商业用途或违反服务条款的场景。

---

## 许可

[GPL-3.0](LICENSE)

---

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) 与 [libxposed API](https://github.com/libxposed/api)
- [WeKit](https://github.com/ujhhgtg/wekit) —— 本模块的设计思路来源
- [jadx](https://github.com/skylot/jadx) / [apktool](https://github.com/iBotPeaches/Apktool) —— 逆向分析工具
