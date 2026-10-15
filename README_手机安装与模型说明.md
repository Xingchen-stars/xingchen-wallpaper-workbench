# 星辰壁纸工作台 3.0 · Android 本地 AI 景深锁屏生成器（源码 / Beta）

这是安卓 App 工程源码，**不是现成 APK**。打包以后可以在 Android 10+ 手机上独立运行，不要求电脑一直开着。

## 已集成的功能

- 手机相册多选导入 JPG/PNG/WebP
- 时钟样式、颜色、日期、透明度调整
- 手指涂抹前景区域与透明前景 PNG 导入
- **本地 ONNX 推理**：动漫 ISNet 1024x1024 模型，手机 CPU 运行
- 首次通过 APP 下载模型，或者从手机文件管理器导入 `.onnx`
- 多张排队 AI 人物抠图（依次处理）
- 单张 JPG/PNG 保存到 `图片/星辰壁纸工作台/`
- 多张 ZIP 保存到 `下载/星辰壁纸工作台/`
- 不需要 Python、Windows 服务或云端抠图 API

## 如何编译 APK（Android Studio）

1. 在 Windows/macOS/Linux 安装 Android Studio。
2. 通过 **Open** 打开项目根目录 `星辰壁纸工作台_Android工程`。
3. 等待 Gradle 同步：JDK 17、Android SDK 35、Gradle 8.9、ONNX Runtime Android 1.23.2。
4. 菜单 Build → Build APK(s)，输出 `app/build/outputs/apk/debug/app-debug.apk`。
5. 复制 APK 到安卓手机安装（允许该文件管理器安装未知来源应用）。

## 通过 GitHub Actions 编译（可不用自己的电脑）

工程自带 `.github/workflows/build-apk.yml`。将**项目根目录的内容**推送到一个 GitHub 仓库 `main` 分支（不是只上传 ZIP）；在 GitHub 的 Actions 中打开 `Build Android APK`，点击 Run workflow。

构建成功后，在对应 Workflow Run 下方 Artifacts 下载 `Xingchen-Wallpaper-Workbench-3.0-debug-apk`，解压后得到 `app-debug.apk`。

> 这是调试签名 APK，适合个人安装测试，不宜直接拿来上架应用商店。发布版需要签名管理、真机测试与优化。

## 模型怎么获取

- 打开 APP → 找到「③ AI 人物识别与景深」→「首次下载模型」
- 下载地址：`https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx`
- 如 GitHub 下载困难，可到 [rembg 项目模型介绍](https://github.com/danielgatis/rembg#models) 查找镜像或下载后通过「从手机导入ONNX」操作。
- 模型约 176MB，不预装到 APK。APP 内下载或导入一次后存入私有目录，下次可以完全离线做 AI 抠图。
- **不要把模型改成 `.zip` 或图片文件**；应选有效的 ISNet Anime ONNX 权重。

## 运行与权限

- 推荐安卓 11 及以上；最低 Android 10（API 29）。
- 图片保存使用 Android MediaStore，无需索取对整个存储空间的读写权限。
- 图片只在本机处理。只有点击「首次下载模型」才会发起网络请求。
- 手机 CPU 处理 1024x1024 ONNX 模型可能比较慢，长时间批量处理可能导致发热。

## 测试状态

- 已通过前端 JavaScript 语法检查、核心接口静态检查。
- 当前运行环境没有 Android SDK，**尚未执行 APK 编译与 Android 真机 ONNX 推理**。代码是可构建的工程初版，不保证一次编译通过；如报错，请提供 Gradle 日志继续修复。
- Chrome 浏览器测试环境限制了本地页面打开，未完成模拟移动端端到端测试。

## 技术/许可证来源

- [SkyTNT/anime-segmentation](https://github.com/SkyTNT/anime-segmentation) （Apache-2.0），模型结构与分割前/后处理思路。
- [danielgatis/rembg](https://github.com/danielgatis/rembg)，模型下载来源说明。
- [ONNX Runtime](https://onnxruntime.ai/) （MIT），Android 推理引擎。
- 请在分发和商业使用前再次核对所下载模型权重及其相关许可。

## 应用与仓库的新名称

- 安卓桌面应用名称：**星辰壁纸工作台**
- GitHub 仓库名（建议）：`xingchen-wallpaper-workbench`
- 安卓包名：`com.xingchen.wallpaper`
- 构建产物名称：`Xingchen-Wallpaper-Workbench-3.0-debug-apk`

旧版「星壁」工程只是开发中的前身，本工程已统一更新用户可见名称。
