# 家里有数 · Android

原生 Android 家庭收纳工具。按「空间 → 容器 → 物品」记录位置，沿用 `design/` 中已确认的交互稿。不使用 WebView，无账号、广告、联网权限或云端依赖。

## 下载安装

- [下载 v0.1.0 APK 测试版](https://github.com/Lijunjie2023/home-store/raw/refs/tags/v0.1.0/downloads/home-inventory-0.1.0-debug.apk)
- [GitHub 发布页](https://github.com/Lijunjie2023/home-store/releases/tag/v0.1.0)
- [安装包校验文件](https://github.com/Lijunjie2023/home-store/blob/v0.1.0/downloads/SHA256SUMS.txt)

`downloads/` 保留已发布的安装包；完整安卓源码、交互稿、测试和构建脚本均在本仓库中。该 APK 为 debug 签名测试版，真机适配范围见下文。

## 已实现

- 空间：新增、重命名、删除；删除非空空间时，将容器及物品迁入其他空间。
- 容器：创建、编辑、具体位置备注；6 种默认封面与相册自定义照片。封面独立于物品照片。
- 物品：系统照片选择器、多图、分类、数量、备注；批量照片可逐张建物品，也可合并为一件物品。
- 查找：按名称、分类、备注搜索并筛选，直接显示完整存放路径。
- 移动与删除：更新物品所属容器；非空容器不能直接删除，删除 App 记录不修改相册原图。
- 本地保存：原子写入应用私有文件，导入照片另存到私有目录，照片长边缩放至 1600 像素。
- 备份恢复：通过系统文件选择器导出含照片的 ZIP；恢复前检查完整性、引用、图片和路径，检查失败不替换原记录。
- 首页顶部搜索和空间栏固定，列表独立滚动；返回手势和编辑放弃确认。

首次安装只有默认空间，没有演示容器或物品。

## 小米 15 适配范围

- Android 10+（API 29），目标 Android 15（API 35）；小米 15 的 Android 15 / HyperOS 为重点适配场景。
- 根据系统报告的密度和可用宽度布局，不按 1200 个物理像素硬编码；大屏自动调整网格列数。
- 处理状态栏、屏幕开孔、手势导航、三键导航与键盘的 WindowInsets；支持旋转及编辑草稿重建。
- Android 13+ 优先使用系统照片选择器；不可用时回退到系统文档选择器，无需申请整个相册访问权限。
- 固定浅色主题，避免自动深色转换破坏照片与背景。
- 没有在实体小米 15 上测试，HyperOS 相册提供程序、真实挖孔、字体放大和安装提示仍需真机验收。

## 安装

交付 APK 为 **debug 签名测试版**，不是应用商店发行版。传到手机后，通过文件管理器打开 APK；如系统提示，按提示允许当前来源安装应用。安装后无需注册。

覆盖升级时需保持相同包名与签名。**卸载或清除应用数据会删除本机记录，请先在设置页导出完整备份**。不同电脑重新生成的 debug 签名可能不同，不能直接覆盖原安装。

## 构建

在 Android Studio 打开仓库根目录。需要 JDK 17+（本次使用 Temurin 21）、SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 8.11.1 已配置官方 SHA-256 校验。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

当前云环境工具放在 `/workspace/shared/android`，执行：

```sh
python3 scripts/cloud-gradle.py
```

脚本配置可写的缓存、Android 用户目录、现有网络代理和系统可信证书，不关闭 TLS 或签名校验。SDK/JDK 已安装在当前云机器；全新机器可通过 Android Studio 的 SDK Manager 安装上述版本。

## 测试

- `InventoryTest`：15 项纯 Java 数据规则测试，覆盖改名关联、非空空间转移、删除限制、搜索、移动、独立封面、无效引用及路径校验。
- `SmokeInstrumentation`：设备集成测试，检查原子持久化、照片缩放、含照片的备份恢复、坏备份保留原数据、原生页面及重建。请在模拟器或专用测试手机运行，会创建验收记录，不用于个人真实数据环境。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.homeinventory.app.test/com.homeinventory.app.SmokeInstrumentation
```

## 数据与限制

- 正式 App 的 ZIP 备份与网页原型 JSON 格式不同；原型数据不自动导入。
- 一件物品有 1–12 张照片；没有名称时显示「未命名物品」。
- 导出的 ZIP 包含明文照片与备注，请自行保管。只备份当前已保存记录，不包括未保存草稿。
- 恢复限制为总照片数据约 500 MB、单张照片 20 MB、清单 10 MB，最多 10,000 个 ZIP 条目；大于限制的备份暂不支持恢复。
- 文件仅存本机。自动系统云备份和设备迁移已关闭，使用 App 内导出/恢复迁移记录。
- 本版暂不含家庭共享、云同步、二维码、AI 识别与相机拍摄。

代码：`Inventory.java` 负责业务规则，`DataStore.java` 负责存储与备份，`MainActivity.java` 负责原生界面及系统交互。
