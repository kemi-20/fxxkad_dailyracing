<p align="center"><img src="icon.png" alt="icon" width="128" height="128"></p>

# 每日赛车 DNS 拦截模块

这是一个面向 LSPosed/Xposed 的 Android 模块，仅对 `com.romielf.mrsc` 生效。模块采用双层去广告策略：一层直接 Hook 应用内的 `cj.mobile` 江湖聚合广告 SDK，使所有广告（开屏/插屏/全屏/激励/原生/Banner/信息流）在加载时立即以 no-fill 失败并跳过，从源头阻止广告展示；另一层 Hook DNS 解析流程，将广告、统计和跟踪域名解析为 `0.0.0.0`，作为纵深防线。同时拦截广告落地页常见的快应用（hap/hwfastapp）跳转。

## 文档

完整的项目文档托管在 [GitHub Wiki](https://github.com/kemi-20/fxxkad_dailyracing/wiki)，涵盖架构设计、Hook 机制、规则引擎与 API 参考：

- [项目概览](https://github.com/kemi-20/fxxkad_dailyracing/wiki/项目概览) — 核心功能、架构与整体流程
- [快速开始](https://github.com/kemi-20/fxxkad_dailyracing/wiki/快速开始) — 环境准备、构建与安装
- [核心架构](https://github.com/kemi-20/fxxkad_dailyracing/wiki/核心架构) · [Hook 机制设计](https://github.com/kemi-20/fxxkad_dailyracing/wiki/Hook机制设计) · [广告拦截器](https://github.com/kemi-20/fxxkad_dailyracing/wiki/广告拦截器) · [规则引擎架构](https://github.com/kemi-20/fxxkad_dailyracing/wiki/规则引擎架构)
- [配置管理](https://github.com/kemi-20/fxxkad_dailyracing/wiki/配置管理) · [开发指南](https://github.com/kemi-20/fxxkad_dailyracing/wiki/开发指南) · [API 参考](https://github.com/kemi-20/fxxkad_dailyracing/wiki/API参考) · [故障排除](https://github.com/kemi-20/fxxkad_dailyracing/wiki/故障排除)

## 功能

- 默认作用域：`com.romielf.mrsc`
- 直接中和 `cj.mobile.CJ*` 全部广告类型（loadAd 立即 onError 并跳过）
- 拦截快应用（hap://、hwfastapp://）跳转
- 命中拦截规则时返回 `0.0.0.0`
- 内置屏蔽域名编译进代码，NPatch 集成模式下同样生效
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
open.e.kuaishou.com
api.e.kuaishou.com
ksapisrv.gifshow.com
zt.gifshow.com
ulog-sdk.gifshow.com
anythinktech.com
toponad.com
da.toponad.com
gdt.qq.com
win.gdt.qq.com
c.gdt.qq.com
v.gdt.qq.com
t.gdt.qq.com
qzs.gdtimg.com
pgdt.gtimg.cn
pgdt.gtimg.com
e.qq.com
bgg.baidu.com
mobads.baidu.com
mobads-logs.baidu.com
afd.baidu.com
als.baidu.com
pangolin-sdk-toutiao.com
api-access.pangolin-sdk-toutiao.com
api-access.pangolin-sdk-toutiao-b.com
pglstatp-toutiao.com
sf3-fe-tos.pglstatp-toutiao.com
dsp.toutiao.com
ad.toutiao.com
is.snssdk.com
i.snssdk.com
log.snssdk.com
extlog.snssdk.com
mon.snssdk.com
toblog.ctobsnssdk.com
ctobsnssdk.com
pangle.io
pangleglobal.com
mssdk.volces.com
sigmob.cn
sigmob.com
adservice.sigmob.cn
jpush.cn
jpush.io
umengcloud.com
umeng.com
ulogs.umeng.com
plbslog.umeng.com
ainfo.umeng.com
msgstat.umengcloud.com
mcc.inf.miui.com
tracking.miui.com
tnc3-aliec2.zijieapi.com
tnc3-alisc1.zijieapi.com
tnc3-bjlgy.zijieapi.com
h.trace.qq.com
dns.weixin.qq.com.cn
szlong.weixin.qq.com
fastappjump-drcn.hispace.hicloud.com
fastappjump-drcn.hispace.dbankcloud.cn
hapjs.org
statres.quickapp.cn
cdn.quickapp.cn
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

## GitHub Actions 集成打包

仓库包含 `Build Integrated RacingDaily APK` workflow。它会：

1. 构建并签名本 Xposed 模块。
2. 下载 LSPatch jar。
3. 以集成模式把模块注入 `origin/racingdaily.apk`。
4. 尽量保留原 APK 的包名、versionCode、versionName、minSdk 和 targetSdk。
5. 使用仓库 secrets 中的证书重新签名最终 APK。
6. 上传 `racingdaily-lspatched.apk` 作为 artifact。

需要配置以下 GitHub Actions secrets：

```text
KEYSTORE_BASE64
KEY_ALIAS
KEYSTORE_PASSWORD
KEY_PASSWORD
```

`origin/racingdaily.apk` 使用 Git LFS 跟踪，首次 clone 后请确保已启用 Git LFS。

## 说明

`app/libs/api-82.jar` 是 Xposed API 的 compileOnly 依赖，仅用于编译，不会打包进 APK。
