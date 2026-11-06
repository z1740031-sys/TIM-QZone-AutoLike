## TIM 空间自动点赞 v1.5

LSPosed 模块：TIM 空间说说刷到自动点赞。

### 安装包

- **`TIM_QZone_AutoLike-v1.5.apk`** — arm64-v8a，minSdk 21 / targetSdk 31，versionCode 6
- SHA-256：`b43bed6bfb236d902f5cab57a01dc073…`（完整值见下方校验说明）

### 功能

- 浏览空间说说时自动点赞
- **仅好友**：跳过非好友/推荐流内容
- **去重**：同一条说说只点一次
- **跳过广告**：识别并跳过推广内容
- 可配置延迟（默认 100ms）

### 安装

1. 安装 APK；
2. 在 LSPosed 中启用本模块，作用域勾选 **TIM**（`com.tencent.tim`）；
3. 打开模块可配置「仅好友」「延迟」等选项；
4. 重启 TIM 生效。

> 需要 LSPosed（API 102+）与已 root 的设备。

### 日志

```
/storage/emulated/0/Android/data/com.tencent.tim/DianZan/DianZan.log
```

### 校验

```bash
sha256sum TIM_QZone_AutoLike-v1.5.apk
```
