<p align="center"><img src="icon.png" alt="icon" width="128" height="128"></p>
# 每日赛车 DNS 拦截模块

这是一个面向 LSPosed/Xposed 的 Android 模块，仅对 `com.romielf.mrsc` 生效。模块通过 Hook 目标应用内的 DNS 解析流程，将广告、统计和跟踪域名解析为 `0.0.0.0`，尽量模拟 AdGuard Home/hosts 级别的域名屏蔽方式，避免直接干扰广告 SDK 初始化造成闪退。

## 功能

- 默认作用域：`com.romielf.mrsc`
- 命中拦截规则时返回 `0.0.0.0`
- 内置拦截日志 UI
- 顶部统计显示原始拦截次数
- 日志列表会合并同一域名 1 分钟内的重复记录
- 支持清空拦截记录

## 使用

1. 安装 APK。
2. 在 LSPosed 中启用模块。
3. 确认作用域包含 `com.romielf.mrsc`。
4. 强行停止每日赛车后重新打开。
5. 打开“每日赛车去广告模块”，刷新查看拦截记录。

## 当前拦截域名

模块会匹配主域名及其子域名，例如 `gdt.qq.com` 与 `foo.gdt.qq.com` 都会命中。

```text
adx.adwangmai.com
gdfp.gifshow.com
e.kuaishou.cn
e.kuaishou.com
anythinktech.com
gdt.qq.com
bgg.baidu.com
mobads.baidu.com
e.qq.com
jpush.cn
jpush.io
umengcloud.com
umeng.com
mcc.inf.miui.com
tracking.miui.com
tnc3-aliec2.zijieapi.com
tnc3-alisc1.zijieapi.com
tnc3-bjlgy.zijieapi.com
toblog.ctobsnssdk.com
sf3-fe-tos.pglstatp-toutiao.com
api-access.pangolin-sdk-toutiao.com
api-access.pangolin-sdk-toutiao-b.com
mssdk.volces.com
h.trace.qq.com
dns.weixin.qq.com.cn
szlong.weixin.qq.com
zt.gifshow.com
ulog-sdk.gifshow.com
easytomessage.com
```

## 构建

项目使用 Android Gradle Plugin 和 Kotlin：

```powershell
gradle assembleDebug
```

本机需要配置 Android SDK。若没有全局配置，可在项目根目录创建 `local.properties`：

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

构建完成后，debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 说明

`app/libs/api-82.jar` 是 Xposed API 的 compileOnly 依赖，仅用于编译，不会打包进 APK。
