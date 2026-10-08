# 课堂录屏

原生 Java 安卓屏幕录制应用，面向平板录课。主应用位于 `app`，兼容探测工具位于 `probe`。

当前版本 **1.2.0**。支持画面尺寸、帧率和码率选择、麦克风或设备声音录制、暂停开关，以及可折叠为右上角圆点的悬浮控制板。

[下载可安装 APK](https://github.com/dadanmiao/course-recorder/releases/latest) · [安装与使用说明](docs/使用说明.md)

## 构建环境

JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3、Android SDK Platform 35、Build Tools 35.0.0。主应用 minSdk 29 / targetSdk 35。AndroidX Activity 1.10.1。

在 Android Studio 中打开项目，配置 Android SDK 路径，使用 JDK 17。仓库不含 `local.properties`、SDK、构建缓存和发布私钥。首次通过 Gradle Wrapper 构建需要联网下载依赖，并按 Android SDK 的正式流程阅读和接受相关许可证。

Windows 构建：`gradlew.bat :app:assembleDebug :app:lintDebug`。

发布构建：`gradlew.bat :app:assembleRelease :app:lintRelease`。默认未配置签名时生成未签名发布包，可使用 Android Studio 的 Generate Signed APK 流程签名。工程还支持环境变量 `COURSE_SIGNING_PASSWORD`，配合工程上级目录 `signing/course-recorder.jks` 中别名为 `course-recorder` 的私钥。原交付安装包的私钥不随源码分发；修改源码后的新签名包不能覆盖原安装包。

## 结构

- `MainActivity`：参数、权限授权和录制控制。
- `RecordingService`：前台服务、倒计时、通知、串行启动/停止以及状态。
- `FloatingRecordingControls`：默认仅显示右上角 48dp 圆点；点按展开控制条，点“收起”恢复圆点。展开位置可选左/右上角并可拖动，支持暂停/继续和结束二次确认；需要用户通过系统界面授予悬浮权限。
- `RecordingTimeline`：单调时钟，剔除暂停时间，视频和声音共用。
- `ScreenFrameRenderer`：SurfaceTexture/EGL 采样屏幕，按目标帧率送入编码器。暂停时不送入画面，避免丢弃已编码 P 帧导致损坏。
- `ScreenRecorderEngine`：MediaProjection、H.264、AAC、AudioRecord、MediaMuxer、MediaStore。
- `RecordingLibraryActivity`：查询本应用可访问的课程文件、SAF 导出、主动分享。
- `PlayerActivity`：内置 VideoView 播放。
- `VideoInspection`：独立 instrumentation，逐轨检查时间戳、解码最新课程视频并检查画面非空白。不要把测试 APK 作为用户安装包分发。

所有导出都由用户选择目标位置。录制状态下不支持修改码率和帧率；暂停开关在开始前固定。API 34 及以上的内容尺寸变化用回调处理，输出视频尺寸固定，按比例适配；较旧容器和中途旋转需真机测试。

1.1.0 增加悬浮控制条。新增 SYSTEM_ALERT_WINDOW 权限，用户自行授权；拒绝授权仍可录制。系统敏感界面可主动隐藏悬浮窗，不绕过系统安全限制。悬浮条可能进入全屏录制画面；不申请无障碍、不伪装系统窗口。卓易通能否把安卓悬浮条显示在鸿蒙原生华为笔记上需真机确认。沿用原项目签名可覆盖更新；源码包不含此签名私钥。

1.2.0 将悬浮控制条默认折叠为右上角圆点；点击圆点展开，点击“收起”重新折叠。折叠时悬浮窗实际缩至 48dp，避免遮挡和拦截笔记页面更大范围的触摸。红点表示录制，黄点表示暂停。原“关闭悬浮条”设置仍有效。

已用 Android 15/API 35 模拟器验证安装、麦克风模式音轨生成、暂停续录、关闭暂停、15/30 FPS、视频逐帧解码、内置播放与导出。模拟器声音质量、鸿蒙原生音频桥接和长录稳定性不是已验证项目。
