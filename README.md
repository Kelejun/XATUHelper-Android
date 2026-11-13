![XATune](docs/assets/readme-header.png)

# XATune for Android

仙宫小助手 Android 版，从教务官网自动拉取课程表、考试安排、学分等信息。同时提供查询宿舍电费、签到与请销假等小功能。

> 查看 iOS 版本仓库请前往 [XATUHelper](https://github.com/Kelejun/XATUHelper)

## 下载

<a href="https://github.com/Kelejun/XATUHelper-Android/releases/latest">
  <img src="./github-android-badge.png"
       alt="Download on GitHub" height="40">
</a>
<a href="https://apps.apple.com/us/app/xatune/id6831484863">
  <img src="https://tools.applemediaservices.com/api/badges/download-on-the-app-store/black/en-us?size=250x83"
       alt="Download on the App Store" height="40">
</a>

## 编译

需要 JDK 17 、Android SDK 36。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
```

APK 按 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 分别构建。构建完成后运行 `python3 scripts/package-release.py`，输出到 `dist/<版本号>/per-abi/`。
除 QA／Benchmark 构建外，Debug 与 Release 构建均无示例账号入口。

## 项目结构

```text
app/src/main/java/com/xatune/app/
  data/       CAS、Cookie、网络、解析、Room、DataStore、Keystore、Repository
  model/      课程、学期、周次、作息与重叠布局
  ui/         MD3 界面、ViewModel、校园 WebView
  platform/   AlarmManager 提醒、WorkManager 维护、AppWidget
app/src/test/         解析、周次、AES 和 HTTP 协议测试
app/src/androidTest/ 原生交互、完整数据流程、存储和 Cookie 测试
app/schemas/          Room 数据库版本
performance/          独立进程的优化构建 UIAutomator 检查
```

网络、HTML 解析和存储在后台执行；Compose 只消费不可变状态。按周过滤后再合并节次，避免单双周课程互相污染。认证串行处理，自动恢复最多每分钟一次、每小时三次；浏览页面不会触发反复登录。

因教务系统、校园网自助平台及缴费服务使用 HTTP，对 `jwgl2018.xatu.edu.cn`、`self.xatu.edu.cn` 和 `jfpay.xatu.edu.cn` 单独放行 HTTP。不忽略 TLS 证书错误。

## 许可

**保留所有权利（All Rights Reserved）**，详见 [LICENSE](LICENSE)。

本声明涵盖本仓库中的源代码、文档、界面设计、图标及其他资源，另有明确声明的内容除外。未经事先书面许可，不得使用、复制、修改、合并、发布、分发、再许可、销售或制作衍生作品，也不得用于商业用途；法律另有规定的除外。仓库公开可见不代表授予上述权利。如需授权，请联系仓库所有者。

第三方依赖及内容仍遵循各自的许可条款。
