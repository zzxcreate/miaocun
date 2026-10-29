# 秒存 · MiaoCun

刷抖音时看到想留的视频或图集，点一下悬浮球就存到相册——**无水印原片**。

`秒存` 是一个纯本地的 Android 小工具：一个可拖动的悬浮球，配合无障碍服务自动完成「分享 → 复制链接」，再用分享链接解析出抖音的原始播放地址（`playwm` → `play`），最后把文件写进系统相册。整个过程不经过任何第三方服务器。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 悬浮球 | 任意界面可拖动、自动贴边，点一下展开操作面板 |
| 一键保存 | 自动点抖音「分享 → 复制链接」，解析后保存无水印视频 |
| 图集保存 | 图文/图集作品会按顺序把每张原图存下来 |
| 截图兜底 | 解析不到时，按设置裁剪屏幕截图保存（Android 11+ 静默截图） |
| 分享进来 | 从抖音系统分享面板「分享到秒存」也能直接保存 |
| 本地记录 | 最近保存的文件在首页可见，点击直接用系统播放器/相册打开 |
| 自定义接口 | 内置解析失效时，可配置自己的解析接口，按 `?url=分享链接` 约定返回 |

## 工作原理

```
┌─────────────┐   点悬浮球   ┌──────────────────────┐
│  悬浮球「存」 │ ──────────▶ │ 无障碍：点「分享」    │
└─────────────┘              │      →「复制链接」    │
                             └──────────┬───────────┘
                                        │ 剪贴板
                             ┌──────────▼───────────┐
                             │ ClipActivity 读剪贴板 │
                             └──────────┬───────────┘
                                        │ 分享链接
                             ┌──────────▼───────────┐
                             │ 解析：iesdouyin 分享页 │
                             │  _ROUTER_DATA → JSON  │
                             │  playwm → play 去水印  │
                             └──────────┬───────────┘
                                        │ 原始地址
                             ┌──────────▼───────────┐
                             │ MediaStore 写入相册   │
                             │ Movies/秒存、Pictures/秒存 │
                             └──────────────────────┘
```

解析顺序（任何一步成功就停）：

1. 自定义接口（若已配置）
2. 分享文本里的直链（`.mp4` / 图片地址）
3. `https://www.iesdouyin.com/share/video/{id}/`、`/share/note/{id}/` 分享页里的 `_ROUTER_DATA`
4. `iteminfo` 接口兜底
5. 都不行 → 提示用「保存当前画面」截图

## 环境要求

- Android 10（API 29）及以上；静默截图需要 **Android 11（API 30）** 及以上
- 构建：JDK 17、Android SDK Platform 35、Gradle 8.9（AGP 8.7.3 / Kotlin 2.0.21）
- 依赖只有四个：`core-ktx`、`appcompat`、`kotlinx-coroutines-android`、`okhttp`

## 构建

### 方式一：Android Studio（最省事）

1. Android Studio 打开 `miaocun` 目录，等 Gradle Sync 完成；
2. `Build → Build Bundle(s)/APK(s) → Build APK(s)`；
3. APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：命令行 / 脚本

```powershell
# Windows：自动找 JDK、SDK，缺 Gradle 时下载 8.9 并生成 wrapper
.\build.ps1

# 打 release（未签名）
.\build.ps1 -Release

# 指定 SDK 路径
.\build.ps1 -SdkPath "D:\Android\Sdk"
```

```bash
# macOS / Linux
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
```

### 方式三：GitHub Actions

推到 GitHub 后 `Actions → build` 会自动编译，产物在 Artifacts 里的 `miaocun-debug-apk`。

> 仓库里没有提交 `gradle-wrapper.jar`（二进制不便入库）。Android Studio 打开时会自动补上；
> `build.ps1` 与 CI 也会自动生成。

## 使用

1. 安装后打开「秒存」，按顺序开启三项权限：
   - **悬浮窗**（显示在其他应用上层）
   - **无障碍服务**（列表里叫「秒存 · 自动分享」）
   - **通知**（看保存进度，可选）
2. 打开首页「显示悬浮球」开关。
3. 刷抖音时点悬浮球「存」：
   - 默认自动分享 → 解析 → 下载；
   - 若自动点击没成功，手动「分享 → 复制链接」，再点面板里的「粘贴解析」。
4. 文件位置：`相册/Movies/秒存`（视频）、`相册/Pictures/秒存`（图片、截图）。

### 截图裁剪

抖音视频通常铺满屏幕，但顶部有状态栏、底部有文案与导航栏。首页「截图裁剪」里按 dp 填四个边距，
「保存当前画面」就会先截整屏、裁掉这些区域再存。默认全 0（不裁剪）。

## 目录结构

```
miaocun/
├─ app/src/main/
│  ├─ java/com/miaocun/app/
│  │  ├─ App.kt                     Application，建通知渠道
│  │  ├─ MainActivity.kt            权限引导、开关、裁剪/接口设置、记录
│  │  ├─ ClipActivity.kt            透明 Activity，读剪贴板
│  │  ├─ ShareActivity.kt           接系统分享
│  │  ├─ accessibility/
│  │  │  └─ AutoShareService.kt     自动点分享 + takeScreenshot
│  │  ├─ overlay/
│  │  │  └─ FloatingService.kt      悬浮球与操作面板
│  │  ├─ download/
│  │  │  └─ DownloadService.kt      前台服务：解析 + 下载 + 入库
│  │  ├─ douyin/
│  │  │  ├─ Http.kt                 OkHttp 封装
│  │  │  └─ DouyinParser.kt         分享链接 → 原始媒体地址
│  │  └─ core/
│  │     ├─ Prefs.kt                本地设置
│  │     ├─ Notifications.kt        通知渠道与样式
│  │     ├─ MediaSaver.kt           MediaStore 写入
│  │     ├─ ScreenshotSaver.kt      截图裁剪保存
│  │     ├─ HistoryStore.kt         保存记录（JSON）
│  │     └─ Util.kt                 时间戳、文件名清洗
│  ├─ res/                          布局、主题、图标、无障碍配置
│  └─ AndroidManifest.xml
├─ build.ps1                        一键构建脚本
├─ tools/check.ps1                  静态自检（XML/资源引用/括号平衡）
└─ .github/workflows/build.yml      CI 编译
```

## 常见问题

**Q：点「存」没反应 / 提示没开无障碍？**
A：系统设置 → 无障碍 → 「秒存 · 自动分享」打开。部分 ROM（MIUI、ColorOS 等）还需要额外允许「后台弹出界面」「自启动」，否则 `ClipActivity` 起不来。

**Q：解析失败？**
A：抖音改版会让解析失效。先试「粘贴解析」（手动复制链接），仍不行就是解析规则要更新；也可以在首页填自定义接口。

**Q：截图全黑？**
A：部分页面（支付、银行、部分视频）有 FLAG_SECURE 防截屏，系统会直接返回黑图，这是系统限制。

**Q：保存的视频有声音没画面 / 打不开？**
A：抖音部分作品是 H.265 编码，老机型播放器可能不支持；文件本身是完整的，可用支持 H.265 的播放器打开。

**Q：会被封号吗？**
A：本工具只读取你主动分享出来的链接，不注入、不修改抖音的任何数据，也不做批量抓取。但自动化操作存在平台规则风险，请自行评估、低频使用。

## 免责声明

仅供个人学习、技术研究与**已获授权内容的本地备份**使用。请尊重创作者版权与平台规则，不要用于批量搬运、二次分发或任何商业用途。因使用本工具产生的一切后果由使用者自行承担。
