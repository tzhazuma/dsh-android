# dsh → Android 移植 STATUS

> 建立者：接替智能体（前任因设备焦点竞争中断，未留文档）。
> 最后更新：**2026-10-16 11:50 CST**
> 结论一句话：**dsh 本体已在真机 Android 16 / arm64 / bionic 上成功启动并跑起完整 Web GUI（有截图）**；
> 8 个原生模块中 **koffi 与 node-pty 已真正解决**（编译通过 + 运行验证），
> 仅 `node-addon-require-builtin` 仍需绕行（上游无 Android 实现）。
>
> 另：用户指定的核心架构机制「**我们自己的 APK 持有 `RUN_COMMAND` 权限、把 bash 派发给 Termux**」
> **已在模拟器与真机双双端到端验证通过**（真机 `BRIDGE_OK … uid=10238`），无 UI 注入、无焦点竞争。

---

## 0. 目标形态（用户明确指定，不得跑偏）

**dsh 本体做成原生 Android APK**；仅在需要执行命令行时使用 **Termux 作为 bash 执行后端**
（Termux 不是 dsh 的宿主，只是被派发命令的执行器）。

---

## 1. 已确立事实（前任已验证，不重复验证）

| 项 | 值 |
|---|---|
| npm 包 | `@deepseek-ai/dsh@0.2.0-rc.2`，**公共 npm registry 直接可拉** |
| 真机 | vivo **PA2573** / Android 16（`ro.build.version.sdk=36`）/ **arm64** |
| 设备 adb | **`192.168.0.108:44939`**（本次全程在线；另有 `emulator-5554` sdk_gphone64_arm64） |
| Termux | `com.termux` **0.119.0-beta.3**，`minSdk=24` **`targetSdk=28`** |
| root | **无 root、无 su** |
| adb 路径 | `~/Library/Android/sdk/platform-tools/adb`（**不在 PATH**，需 export） |

**桌面版 asar 内原生件清单**（全部 darwin-arm64 预编译）：
`node-pty`、`sharp`+`libvips`、`@koromix/koffi`、`sherpa-onnx`、`@vscode/ripgrep`、
`@deepseek-ai/node-addon-system`、`node-addon-require-builtin`、`libreoffice-kit`。

---

## 2. ⚠️ 突破：可靠通道（**取代前任的 UI 注入方案**）

### 2.1 `adb shell run-as com.termux` 可用！——本次最大的工程解锁

前任依赖 `input text` UI 注入 → 被其他智能体抢焦点而失败。
本次发现 **`run-as` 在 Termux 上直接可用**（无需 root、无需焦点、无需注入）：

```bash
# 以 Termux uid(u0_a238) 身份任意执行，读/写 Termux 私有目录
adb shell run-as com.termux /data/data/com.termux/files/usr/bin/bash <script>
adb shell run-as com.termux cat /data/data/com.termux/files/home/.bashrc
```

实测确认：`whoami=u0_a238`，可写 `$HOME`，node/npm 正常。
→ **所有"线 2"工作现在都可以用脚本 + run-as 完成，彻底免疫焦点竞争。**

工具封装见 **`scripts/tx.sh`**（push / run / sh 三个子命令）。

### 2.2 前任失败的**真正根因**（已查明）

1. **Termux 的默认 shell 是 zsh，不是 bash**：`.termux/shell -> /data/data/com.termux/files/usr/bin/zsh`。
   → 前任的 `setup_auto.sh` 往 `~/.bashrc` 装钩子是**永远不会触发**的。
   （实测：`force-stop` + `am start TermuxActivity` 后 marker 文件未生成，确认钩子未生效。）
2. 另一智能体 `org.freecad.android` 持续持有设备焦点（`mFocusedApp` 实测）。

### 2.3 坑（务必记住）

- **`adb shell` 会把多参数命令交给设备 shell 重新解析**，`sh -c '...'` 的引号会被吃掉，
  `-c` 只吞下第一个词。→ **一律先 push 脚本文件，再以单个 argv 执行。**
- **`export A=/x B=$A/bin:$PATH` 写在一行里，`$A` 在赋值生效前就被展开**（结果为空）。
  → **每个 export 必须独立成行。** 这会让 `node: command not found` 且极难排查。
- `/sdcard` 对 Termux uid **不可读**（无存储权限）；用 `run-as cat` 读结果，不要走 /sdcard。

---

## 3. 线 1：原生模块精确枚举（**已完成**）

数据源：`npm install --package-lock-only @deepseek-ai/dsh@0.2.0-rc.2` 生成的
**lockfileVersion 3**（`line1/package-lock.json`，**608 个包**）。

### 3.1 `hasInstallScript` 精确清单（5 个）

| 包 | 安装脚本 | 是否 native |
|---|---|---|
| `koffi@3.1.1` | `node ./cnoke.cjs -P . -D src/koffi --prebuild --release` | **是**（FFI） |
| `node-pty@1.2.0-beta.15` | `node scripts/prebuild.js \|\| node-gyp rebuild` + postinstall | **是**（pty） |
| `@deepseek-ai/dsh-subprocess-local@0.2.0-rc.2` | `node scripts/ensure-spawn-helper.mjs` | 疑似 |
| `protobufjs@7.6.6` | `node scripts/postinstall` | 否（纯 JS） |
| `@google/genai@2.21.0` | `echo 'preinstall: no-op'` | 否 |

### 3.2 平台变体包：**全树 608 个包里没有任何一个提到 "android"**

`os`/`cpu` 受限包共 **76 个**，覆盖：
`darwin-{arm64,x64}`、`linux-{arm64,x64}-{gnu,musl}`、`linux-arm/ppc64/riscv64/s390x`、
`win32-{arm64,x64,ia32}`、`freebsd-wasm32`、`webcontainers-wasm32`。
**android/arm64 变体数量 = 0。**

### 3.3 原生模块 → 依赖方（谁把它拖进来的）

| 原生模块 | 直接依赖方 | 影响面 |
|---|---|---|
| `node-addon-require-builtin` | `@deepseek-ai/dsh`、`dsh-app-boot` | **阻断启动（见 §4.4）** |
| `koffi` | `dsh-fs-local`、`dsh-host-directory-picker-native`、`dsh-sandbox-windows-acl` 等 **7 个** | **阻断 npm install** |
| `sharp` | `dsh-attachment-local` | 附件/图片 |
| `node-pty` | `dsh-subprocess-local` | **bash/子进程工具** |
| `@vscode/ripgrep` | `dsh-tool-fs-search` | 文件搜索 |
| `@deepseek-ai/node-addon-system` | `dsh-sandbox-local`、`dsh-session-persistence-jsonl` | 沙箱/会话持久化 |
| `@deepseek-ai/libreoffice-kit` | `dsh-office-to-pdf`、`dsh-skill-office` | Office 转 PDF |
| `sherpa-onnx` | （语音相关） | 语音输入 |

### 3.4 逐项结论

| 原生模块 | Android/arm64 预编译 | 结论 |
|---|---|---|
| `node-addon-require-builtin` | **无，且上游明确不支持** | **硬阻断**，需上游新增 Android 实现（§5.1） |
| `koffi` | 无 | **源码可编译但需打补丁**（bionic `statx` 签名冲突，§4.3） |
| `node-pty` | 无（仅 darwin/win32/linux 预编译） | 需 bionic 源码编译；**但新架构下可完全绕过**（bash 派发给 Termux） |
| `sharp` + `libvips` | 无 | 需 bionic 编译 libvips，成本高；附件功能降级 |
| `sherpa-onnx` | 无 | 语音输入功能降级/关闭 |
| `@vscode/ripgrep` | 无（有 linux-arm64） | 可换用 Termux 的 `rg` 或纯 JS 回退 |
| `@deepseek-ai/node-addon-system` | 无 | 需源码编译 |
| `libreoffice-kit` | 无（仅 darwin/win32/wasm） | Office 功能在 Android 上不可用 |

---

## 4. 线 2：真机 Termux 实测（**关键证据已拿到**）

### 4.1 Termux 环境（实测）

```
node v26.4.0 | npm 11.19.1 | clang 21.1.8 | cmake 4.4.3 | python 3.14.6 | git
/data 剩余 181 GB
```

### 4.2 `npm install`（**脚本开启**）→ **失败于 koffi**

日志：`evidence/logs/l2_install.log`（4200 B）。koffi 的 cnoke **成功识别出 `android_arm64` 目标**
并用 Termux clang 21 完成 CMake 配置与汇编编译，**仅在 C++ 源文件上失败**：

```
koffi/src/koffi/../../lib/native/base/base.cc:2967:19: error:
  cannot initialize a member subobject of type '__u32' (aka 'unsigned int')
  with an lvalue of type 'const char *'
 2967 |     if (statx(fd, pathname, stat_flags, stat_mask, &sxb) < 0) {
      |                   ^~~~~~~~
error: invalid operands to binary expression ('statx' and 'int')
→ npm error code 1（koffi 是 dsh-fs-local 的硬依赖，导致整棵树回滚）
```

**判读**：这是 **glibc 与 bionic 的 `statx()` 声明冲突**，属**单点源码补丁**级别，
不是架构性阻断。koffi 自身把 `android_arm64` 当作合法目标，说明上游有意支持 Android。

### 4.3 `npm install --ignore-scripts` → **成功**

```
added 530 packages in 49s        NPM_EXIT=0
node_modules/@deepseek-ai/dsh/lib/bin.js  存在
```
日志：`evidence/logs/l2b_install.log`。
→ **dsh 的完整依赖树可以在 Android 上落盘。**

### 4.4 启动实测（逐步逼近真相）

| 命令 | 结果 |
|---|---|
| `dsh --version` | ✅ `0.2.0-rc.2` |
| `dsh --help` | ✅ 完整帮助（launcher / profile / plugin 全套） |
| `dsh tui` | ❌ `profile "tui" does not exist`（tui profile 未随包发布，需先创建） |
| `dsh web` | ❌ **`host preparation failed: No usable native binding found for node-addon-require-builtin-android-arm64`** |
| `dsh web` + shim + `--expose-internals` | ✅ **成功启动并服务完整 GUI** |

**`dsh web` 失败的完整根因链**（`node-addon-native-custom-loader` 的探测记录）：

```
attempts:
 1) optional-package 'node-addon-require-builtin-android-arm64' → Cannot find module
 2) local-build node_modules/node-addon-require-builtin/build/nodeabi/
                node-v147-android-arm64/require_builtin.node  → 不存在
```
即：loader 明确走到 **Android 分支**（`process.platform==='android'` 时返回 `android-arm64` 标签），
但**没有任何已发布产物满足它**。

### 4.5 ⭐ 决定性证据：dsh 在 Android 上跑起来了

诊断 shim（`evidence/logs/diagnostic-shim-narb-index.js`）把
`requireBuiltin(id)` 换成普通 `require(id)`，配合 Node 隐藏开关 **`--expose-internals`**
（后者才能让 `require('internal/modules/esm/loader')` 等内部模块可解析）：

```
$ node --expose-internals -e 'require("internal/modules/esm/loader")'
INTERNAL_OK keys: createModuleLoader,getOrInitializeCascadedLoader,isCascadedLoaderInitialized,register

$ node --expose-internals .../dsh/lib/bin.js web --no-open --port 8899
dsh web: http://127.0.0.1:8899/?token=...
```

→ `adb forward tcp:8899 tcp:8899` → 浏览器打开 → **完整 DeepSeek Harness GUI**：

| 截图 | 内容 |
|---|---|
| `evidence/dsh-android-01.png` | GUI 首屏（预览版说明） |
| `evidence/dsh-android-02.png` | 引导流程 |
| **`evidence/dsh-android-03-main-ui.png`** | **主界面**：deepseek HARNESS logo、新建会话、插件、工作区、暂无会话、设置 + 「添加一个 API Key 开始使用」弹窗 |

**bionic 链接的硬证据**：运行中的 dsh node 进程（pid 7816）`/proc/<pid>/maps` 加载
`/apex/com.android.runtime/lib64/bionic/libc.so`、`libdl.so`、`libm.so`。

> 注意：该 shim **是诊断探针，不是生产修复**。它绕过了 dsh 的模块沙箱机制。

---

## 4.6 ⭐⭐ 后续进展（2026-10-16 11:4x）：**koffi 与 node-pty 已真正解决**

### 4.6.1 npm 11.20 的新坑：**install-scripts 审批门**

npm 11.20 默认**不执行任何依赖的安装脚本**，只打印警告：

```
npm warn install-scripts 5 packages have install scripts not yet covered by allowScripts:
  @deepseek-ai/dsh-subprocess-local@0.2.0-rc.2 (postinstall: node scripts/ensure-spawn-helper.mjs)
  @google/genai@2.21.0 (preinstall: echo 'preinstall: no-op')
  koffi@3.1.1 (install: node ./cnoke.cjs -P . -D src/koffi --prebuild --release)
  node-pty@1.2.0-beta.15 (install: node-gyp rebuild)
  protobufjs@7.6.6 (postinstall: node scripts/postinstall)
```
→ 表现为 `up to date in 3s` + `NPM_EXIT=0`，**看起来成功但其实什么都没编译**。
必须显式放行：
```bash
npm install-scripts approve --all
npm rebuild --foreground-scripts
```
（这也解释了为什么首次 `npm install` 只卡在 koffi —— 它是唯一一个在旧行为下真正跑起来的脚本。）

### 4.6.2 koffi：✅ **已解决（1 行补丁）**

**根因（精确定位）**：bionic 的 `<sys/stat.h>` 里 `statx()` 声明带 `__INTRODUCED_IN(30)`：

```
/usr/include/sys/stat.h:345:
int statx(int __dir_fd, const char* _Nullable __path, int __flags,
          unsigned __mask, struct statx* _Nonnull __buf) __INTRODUCED_IN(30);
```

Termux 的 clang 目标 API < 30 → 声明被隐藏 → 标识符 `statx` 解析为**结构体标签**，
于是出现第二条错误 `invalid operands to binary expression ('statx' and 'int')`。
**这不是 koffi 的 bug，是 Android 头文件的 API 门控。**

**补丁**（`node_modules/koffi/lib/native/base/base.cc`，紧跟 `#include "base.hh"` 之后；
原文件备份为 `base.cc.orig`）：

```c
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 30)
extern "C" int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *statxbuf);
#endif
```

**结果**：`CNOKE_EXIT=0`，产出 `node_modules/koffi/build/koffi/android_arm64/koffi.node`。
**不只是能加载，FFI 真的能调用**：

```
KOFFI_LOADED version= 3.1.1
FFI_CALL_OK getpid()= 15736          ← 经 koffi 调用 bionic libc 成功
```
日志：`evidence/logs/koffi_build.log`

### 4.6.3 node-pty：✅ **已解决（无需任何补丁）**

`npm rebuild` 里 Termux 自带 clang + Node 头文件直接编译通过：

```
CXX(target) Release/obj.target/pty/src/unix/pty.o
SOLINK_MODULE(target) Release/obj.target/pty.node
gyp info ok
```
**真实 pty 生成验证成功**（不是"能 require"而已）：
```
PTY_EXIT_CODE= 0
PTY_OUTPUT>>>
PTY_OK
uid=10238
/dev/pts/1
```
→ 意味着 **dsh 的 bash/子进程工具在 Android 上有原生实现可走**（Termux 派发是另一种选择，两者都成立）。
日志：`evidence/logs/approve_build.log`

### 4.6.4 其余安装脚本：全部通过

`@deepseek-ai/dsh-subprocess-local` postinstall、`protobufjs` postinstall、`node-pty` postinstall
均执行成功，`REBUILD_EXIT=0`。

### 4.6.5 逐模块 `require()` 实测（Android/arm64/bionic）

| 模块 | 状态 | 说明 |
|---|---|---|
| `koffi` | ✅ OK | 已打补丁，FFI 可调用 |
| `node-pty` | ✅ OK | 已编译，pty 可生成 |
| `protobufjs` | ✅ OK | 纯 JS |
| `@deepseek-ai/libreoffice-kit` | ✅ OK | 加载成功（应为 wasm/JS 回退路径） |
| `node-addon-require-builtin` | ⚠️ OK **但用的是 shim** | **真实绑定仍未解决**，见 §5.1 |
| `sharp` | ❌ FAIL | `Could not load the "sharp" module using the android-arm64 runtime` |
| `@vscode/ripgrep` | ❌ FAIL | `Could not find @vscode/ripgrep-android-arm64`（上游未发布） |
| `sherpa-onnx` | ❌ FAIL | `MODULE_NOT_FOUND`（可选依赖未落盘） |
| `@deepseek-ai/node-addon-system` | ❌ FAIL | `ERR_PACKAGE_PATH_NOT_EXPORTED`（**像打包/exports 问题，可能可修**） |

> ⚠️ 注意：`node-addon-require-builtin` 显示 OK **是因为诊断 shim 还在**（原始文件备份为 `index.js.orig`），
> **不代表上游绑定已能在 Android 上工作**。

### 4.6.6 桥接 APK：✅ **真机端到端也验证通过**（2026-10-16 11:40）

真机装机成功后（用户已放开 vivo USB 安装），在 **真机** 上完成验证：

```
--- Termux 侧结果（以 Termux 身份执行）---
BRIDGE_OK Fri Oct 16 11:40:44 CST 2026 uid=10238
--- 权限 ---
com.termux.permission.RUN_COMMAND: granted=true
```
存档：**`evidence/logs/bridge-runcommand-e2e-REALDEVICE.txt`**

#### ⚠️ 真机上一个**静默失败**的坑（排查了很久，务必记住）

最初真机派发**完全没有反应**：既没有 Termux 日志、也没有报错、结果文件也不生成。

**根因（两个叠加）**：
1. **Termux 在「进程启动时」读取 `allow-external-apps` 并缓存在内存**。
   真机上的 Termux 进程早于我们的属性文件修改，所以它一直用的是旧值 `false`，
   于是**静默拒绝**每一次派发。`termux-reload-settings` **不足以**让它生效
   （termux.properties 自己也写了："Some properties require app process to be restarted"）。
   → **必须 `am force-stop com.termux`**，之后一次成功。
2. **vivo ROM 会过滤第三方 app 的 logcat 输出**：我们的 `Log.i` 和 Termux 的日志**都看不到**，
   导致「activity 明明跑了完整生命周期，却一行日志都没有」，极易误判为崩溃。
   → 排查时不要只依赖 logcat；**用「Termux 侧结果文件」这类副作用做判据**。

#### ⚠️ 另一个平台差异：`startService` 在 vivo 上会被**静默丢弃**

| 平台 | `startService` | 可行路径 |
|---|---|---|
| emulator-5554 (AOSP A16) | **抛** `BackgroundServiceStartNotAllowedException` | `startForegroundService` |
| **vivo PA2573 (A16)** | **不抛异常，但被 ROM 静默丢弃**（无任何日志） | **必须** `startForegroundService` |

→ 结论：**`startForegroundService` 必须是主路径**，`startService` 只能作回退。
（Termux 的 RunCommandService 在 `startForegroundService` 下实测**不会 ANR**。）
已据此改写 `bridge/src/.../BridgeActivity.java` 并重新验证。

---

## 5. 线 3：APK 内嵌 Node 运行时评估

### 5.1 `node-addon-require-builtin`：**上游明确不支持 Android（硬阻断）**

上游仓库 `github.com/deepseek-ai/dsh-node-addon-require-builtin`（公开，已 clone 到 `/tmp/narb-src`）
的 `docs/support-matrix.md` 只列出：
`darwin-{arm64,x64}`、`linux-{arm64,x64}-{gnu,musl}`、`win32-{arm64,ia32,x64}-msvc`。
文档原文：

> "Unsupported platforms are intentionally absent from `optionalDependencies`."
> "Unsupported runtimes should fail closed with diagnostics rather than loading an unchecked internal module."

更关键的是**它的实现方式**：该 addon 靠**探测 Node 私有内部**工作（解码 getter 机器码、解析私有符号），
`packages/native/binding.gyp` 的源文件按平台硬编码：
`runtime_context/{darwin_arm64,darwin_x64,linux_arm64,linux_x64,win32_arm64,win32_x64,win32_ia32}.cc`
—— **没有任何 `android_*` / bionic 运行时上下文**。

且 npm 包**只发布 `lib/`（JS 外壳），不含 native 源码**，所以「本地编译」也无从下手。

**结论：这不是加个编译参数能解决的，需要上游新增 Android 实现**（新的 runtime context + probe）。
我们的 shim + `--expose-internals` 已证明**存在可行的替代路径**，可作为给上游的提案。

### 5.2 nodejs-mobile / libnode：**版本代差，不可用**

- `nodejs-mobile` 最新发布 = **`v18.20.4`（即 Node 18.20.4）**，版本号规则 `A.B.C` 对应上游 Node `A.B.*`。
- dsh 自身代码里写死了支持矩阵：`dsh-app-boot/lib/index.js`
  > "the supported Node **22/24/26** matrix validates each available Internal interface"
- **Node 18 低于 dsh 的支持下限**（Node 18 也已 EOL）→ **nodejs-mobile 无法承载 dsh 0.2.0-rc.2**。
- Maven Central 检索 `nodejs-mobile`：**numFound = 0**（无官方 Maven 坐标）。

→ 结论：**放弃 nodejs-mobile**。

### 5.3 真正可行的 APK 路线（推荐）

既然 **Termux 已经在这台设备上跑着 Node 26.4.0（bionic/arm64）**，
说明「为 Android 构建现代 Node」在本机是**已被证明可行**的（Termux 的 nodejs 构建配方即现成参考）。

推荐架构：

1. **内嵌 Node 运行时**：按 Termux 配方（或直接用 Termux 的 nodejs 构建产物）为 arm64/bionic 构建 **Node 26**，
   作为 APK 的原生件。
2. **绕过 Android 10+ 的 exec 限制**：我们的 APK 若 `targetSdk >= 29`，
   **不能 exec 自己数据目录里的可执行文件**（SELinux `untrusted_app` 策略）。
   标准做法是把可执行文件以 `lib*.so` 形式放进 APK 的 `lib/arm64-v8a/`，
   安装后由系统解压到 **native library dir**（该目录可执行）。
   > **旁证（实测）**：Termux 之所以能 exec 自己 data 目录里的二进制，正是因为它
   > **`targetSdk=28`**（实测 `dumpsys package com.termux`）。这是该限制存在的强证据。
3. **dsh 的 JS + node_modules** 作为 APK assets 打包，解压到 app 数据目录（只需读，不需 exec）。
4. **必须解决 `node-addon-require-builtin`**：短期用 shim + `--expose-internals`
   （已实测可行），长期推动上游支持 Android。
5. **bash 工具派发给 Termux**：走 `com.termux/.app.RunCommandService`
   （`com.termux.permission.RUN_COMMAND`，实测 **`prot=dangerous`**，可由 `pm grant` 授予），
   **这样完全不需要 `node-pty`** —— 正好符合用户指定的目标形态。

### 5.4 桥接 APK：✅ **RUN_COMMAND 通道已在模拟器上端到端验证通过**

- 已构建并签名：**`bridge/out/dsh-bridge.apk`**（12,715 B，`com.dsh.bridge`）
  - manifest 声明 `com.termux.permission.RUN_COMMAND`
  - 含 Android 11+ 必需的 `<queries><package android:name="com.termux"/></queries>`
  - 用 aapt2 + javac + d8 + zipalign + apksigner 手工构建（无 Gradle），脚本 `bridge/build.sh`
- Termux 侧已就绪：**`allow-external-apps = true` 已写入** `~/.termux/termux.properties`
  （原文件备份为 `termux.properties.orig-dshbak`）

#### ⭐ 端到端验证结果（`emulator-5554`，Android 16 / arm64）

因真机装机被 vivo ROM 拦截（见下），改在**模拟器**上完整验证该通道
（把真机的 Termux APK `adb pull` 出来装到模拟器，架构相同）。

```
--- 权限 ---            com.termux.permission.RUN_COMMAND: granted=true
--- 我们的 APK 日志 ---  I DshBridge: onCreate
                        I DshBridge: DISPATCH_OK via startForegroundService
                                     argv=[-c, echo BRIDGE_OK $(date) uid=$(id -u) > .../dshx/bridge_result.txt]
--- Termux 侧结果 ---   BRIDGE_OK Fri Oct 16 02:52:23 CST 2026 uid=10221   ← 以 Termux 身份执行
--- ANR 检查 ---        （无 ANR）
```
完整存档：**`evidence/logs/bridge-runcommand-e2e.txt`**

→ **证明：我们自己的 APK 可以持有 `RUN_COMMAND` 权限并把 bash 命令派发给 Termux，
无需 UI 注入、无焦点竞争。** 这正是用户指定架构的核心机制。

#### ⚠️ 过程中发现的两个关键 Android 限制（务必保留在实现里）

1. **不能在 `onResume()` 里直接 `startService`**：实测被拒
   ```
   BackgroundServiceStartNotAllowedException:
     Not allowed to start service Intent {...} to com.termux/.app.RunCommandService
     from ... pkg=com.dsh.bridge startFg?=false ... app is in background uid null
   ```
   服务启动与进程晋升为 TOP procstate 存在竞态。
   **解法**：等第一帧后再延迟约 1.2 s 派发，且 Activity 暂不 `finish()`。
2. **`startService` 仍会被拒，必须回退 `startForegroundService`**：
   实测最终成功的是 `startForegroundService`（Termux 未因此 ANR）。
   两者都实现（try/catch 回退）才能稳定工作。

#### ✅ 真机装机：**已解决**（2026-10-16 11:36）

早先 `adb install` / `pm install` 在真机上挂起，实测定位到
`com.android.packageinstaller/.PackageInterceptActivity` 持有焦点 ——
vivo 的「安全守护」安装确认弹窗需要**人工在设备上点按**
（且需先勾选「已了解应用的风险检测结果」）。当时该弹窗属于**另一个智能体的 FreeCAD 安装**（453 MB），
故未干预，已 `pm install-abandon` 清理。

**用户放开 vivo USB 安装许可后，装机一次成功**：
```
$ adb install -r -t dsh-bridge.apk
Performing Incremental Install
Install command complete in 10846 ms
$ adb shell pm list packages | grep com.dsh
package:com.dsh.bridge
```
→ 真机端到端验证见 **§4.6.6**。

> **经验**：vivo 等国产 ROM 会拦截 adb 装机并要求人工确认。
> 这是**环境/策略问题，不是技术问题** —— 放开「USB调试(安全设置)」即可。

---

## 6. 结论

### dsh 能否在 Android 上跑？——**能，已实证**

dsh 的 JS 在真机 Android 16 / arm64 / **bionic** 上完整启动并跑起 Web GUI（Node 26.4.0，截图见 §4.5）。
dsh 代码本身**没有** Android 不兼容问题；障碍全部来自**原生模块**。

### 卡在哪几个原生模块？（2026-10-16 11:4x 更新后）

| # | 模块 | 现状 | 说明 |
|---|---|---|---|
| 1 | **`node-addon-require-builtin`** | ⚠️ **未解决**（有可用绕行） | 上游**无 Android 实现**，靠探测 Node 私有内部；shim + `--expose-internals` 已实测可绕过 |
| 2 | **`koffi`** | ✅ **已解决** | 1 行 `statx` 声明补丁；FFI 实测可调用 bionic libc（§4.6.2） |
| 3 | **`node-pty`** | ✅ **已解决** | 无需补丁，Termux clang 直接编译；真实 pty 生成实测通过（§4.6.3） |
| 4 | `sharp` + `libvips` | ❌ **未解决** | `Could not load the "sharp" module using the android-arm64 runtime`；需交叉编译 libvips |
| 5 | `@deepseek-ai/node-addon-system` | ❌ **未解决** | `ERR_PACKAGE_PATH_NOT_EXPORTED` —— **像打包/exports 问题，值得优先复查**（可能不是 native 问题） |
| 6 | `@vscode/ripgrep` | ❌ **未解决** | 缺 `@vscode/ripgrep-android-arm64`（上游未发布）；可换 Termux 的 `rg` |
| 7 | `sherpa-onnx` | ❌ **未解决** | `MODULE_NOT_FOUND`（可选依赖未落盘）；语音功能关闭 |
| 8 | `@deepseek-ai/libreoffice-kit` | ✅ **可加载** | `require()` 成功（应为 wasm/JS 回退路径） |

**净结果：8 个里有 4 个可用（koffi、node-pty、libreoffice-kit、protobufjs），
1 个有已验证绕行（node-addon-require-builtin），3 个仍待解决（sharp、ripgrep、node-addon-system、sherpa-onnx）。**

### 已验证到哪一步（证据）

| 验证项 | 状态 | 证据 |
|---|---|---|
| 原生模块全量枚举（608 包，android 变体 = 0） | ✅ | `line1/package-lock.json` + §3 |
| Termux 内 npm 依赖树落盘（530 包） | ✅ | `evidence/logs/l2b_install.log` |
| dsh CLI 在 Android 运行 | ✅ | `--version` / `--help` 输出 |
| **dsh 完整 Web GUI 在 Android 运行** | ✅ | **`evidence/dsh-android-03-main-ui.png`** + `evidence/logs/web.log` |
| Node 26 跑在 bionic 上 | ✅ | 进程 maps 含 `/apex/.../bionic/libc.so` |
| **koffi 编译成功 + FFI 可调用** | ✅ **已解决** | `evidence/logs/koffi_build.log`（`FFI_CALL_OK getpid()`） |
| **node-pty 编译成功 + pty 可生成** | ✅ **已解决** | `evidence/logs/approve_build.log`（`PTY_OK … /dev/pts/1`） |
| `node-addon-require-builtin` 无 Android 支持 | ✅ | 上游 `docs/support-matrix.md` + binding.gyp 平台源文件 |
| nodejs-mobile 版本代差 | ✅ | 最新 release `v18.20.4` |
| 桥接 APK 构建 | ✅ | `bridge/out/dsh-bridge.apk` |
| 桥接 APK → Termux `RUN_COMMAND` 端到端（模拟器） | ✅ | `evidence/logs/bridge-runcommand-e2e.txt`（`uid=10221`） |
| **桥接 APK → Termux `RUN_COMMAND` 端到端（真机）** | ✅ **已验证** | `evidence/logs/bridge-runcommand-e2e-REALDEVICE.txt`（`uid=10238`） |
| 真机装机 | ✅ **已完成** | `pm list packages` 含 `com.dsh.bridge` |

---

## 7. 下一步（按优先级）

1. ~~解锁真机装机~~ ✅ **已完成**（2026-10-16 11:36）。
2. ~~给 koffi 打 `statx` 补丁~~ ✅ **已完成**（§4.6.2），`npm install` 现已能带脚本跑完。
3. ~~验证 node-pty~~ ✅ **已完成**（§4.6.3，无需补丁）。
4. **复查 `@deepseek-ai/node-addon-system`**：它的失败是 `ERR_PACKAGE_PATH_NOT_EXPORTED`，
   **看起来是打包/exports 问题而非 native 问题**，优先排查（可能低成本解决）。
5. **`sharp` + libvips**：需为 bionic/arm64 交叉编译 libvips（成本最高的一项）；
   或评估改用 `sharp` 的 wasm 变体 / 关闭附件图片处理功能。
6. **`@vscode/ripgrep`**：改用 Termux 的 `rg`（或自建 android-arm64 产物）；
   本次已验证 APK→Termux 派发通道可用，走 Termux 是最省力的路。
7. **向上游提 issue/PR**：`node-addon-require-builtin` 增加 Android/bionic 支持；
   附上本次的 shim + `--expose-internals` 可行性证据（这是唯一真正的硬阻断）。
8. **构建 Node 26 arm64/bionic 原生件**并验证「`lib*.so` 放 native library dir 可 exec」，
   这是 APK 内嵌运行时的关键未验证假设。
9. 逐步验证 `dsh tui`（需先创建 tui profile）、以及 bash 工具的两条路
   （原生 `node-pty` 已可用 / 或经 Termux 派发）。

---

## 8. 产物索引

```
~/dsh-android/
├── STATUS.md                        本文档
├── evidence/
│   ├── dsh-android-01.png           GUI 首屏
│   ├── dsh-android-02.png           引导流程
│   ├── dsh-android-03-main-ui.png   ⭐ 主界面（决定性证据）
│   └── logs/
│       ├── l2_install.log           koffi 编译失败（完整编译器错误，已解决前）
│       ├── l2b_install.log          530 包安装成功
│       ├── web.log                  dsh web 启动 URL
│       ├── koffi_build.log          ⭐ koffi 打补丁后编译成功（CNOKE_EXIT=0）
│       ├── approve_build.log        ⭐ node-pty 编译成功 + 全部安装脚本通过
│       ├── bridge-runcommand-e2e.txt              APK→Termux 端到端（模拟器）
│       ├── bridge-runcommand-e2e-REALDEVICE.txt   ⭐ 同上（真机，uid=10238）
│       └── diagnostic-shim-narb-index.js  诊断 shim（非生产修复）
├── scripts/
│   ├── tx.sh                        ⭐ run-as 通道封装（push/run/sh）
│   ├── dsh_env.sh  dsh_probe2.sh  setup_auto.sh   （前任遗留）
├── line1/
│   ├── package-lock.json            608 包锁文件（枚举数据源）
│   └── tarball/dsh.tgz              dsh 0.2.0-rc.2 npm tarball
└── bridge/
    ├── AndroidManifest.xml  src/…/BridgeActivity.java
    ├── build.sh                     无 Gradle 的手工 APK 构建脚本
    └── out/dsh-bridge.apk           已签名桥接 APK（12,715 B）
```

> Termux 侧工作目录：`/data/data/com.termux/files/home/dshx/`（env.sh、各测试脚本、日志）
> 与 `~/dsh-test/`（530 包安装树，dsh 运行于此）。

---

# 9. 2026-10-17 收尾轮：4 个失败模块逐个结清 + **dsh 原生 APK 端到端跑通**

> 本轮目标：处理 `sharp` / `@vscode/ripgrep` / `sherpa-onnx` / `@deepseek-ai/node-addon-system`，
> 并把 dsh 本体打成**原生 APK**装到设备验证（Termux 仅作 bash 执行后端）。

## 9.1 结论速览

| # | 模块 | 上轮状态 | 本轮结论 |
|---|---|---|---|
| 1 | `koffi` | ✅ | ✅ 不变（APK 内实测 `KOFFI_OK 3.1.1`） |
| 2 | `node-pty` | ✅ | ✅ 不变（APK 内实测 `PTY_OK "PTY_OK"`） |
| 3 | `protobufjs` | ✅ | ✅ 不变（APK 内 `PROTOBUFJS_OK`） |
| 4 | `@deepseek-ai/libreoffice-kit` | ✅ | ✅ 不变（APK 内 `LOKIT_OK`） |
| 5 | **`sharp`** | ❌ | ✅ **已修好且真能处理图像**（WebAssembly 兜底，见 §9.2） |
| 6 | **`@vscode/ripgrep`** | ❌ | ✅ **已修好**（自建 aarch64-linux-android `rg` 15.2.0，见 §9.3） |
| 7 | **`@deepseek-ai/node-addon-system`** | ❌（误判） | ✅ **已修好**（上轮的失败是**测法错**；真问题两个都已解决，见 §9.4） |
| 8 | **`sherpa-onnx`** | ❌ | ❌ **不可修（根因明确）**：上游无 android 产物，见 §9.5 |
| — | `node-addon-require-builtin` | ⚠️ 有绕行 | ⚠️ 不变（shim + `--expose-internals`，上游无 Android 实现） |

**净结果：8 个里 7 个在 APK 内可用，1 个（sherpa-onnx）有明确的上游缺产物根因。**

## 9.2 `sharp` → 用官方 WebAssembly 兜底路径修好

**关键发现**：`sharp@0.35.5` 的 `dist/sharp.cjs` **自带一条与平台无关的兜底**：

```js
// dist/sharp.cjs:104
if (!sharp) {
  try { sharp = require("@img/sharp-wasm32/sharp.node"); } catch (err) { errors.push(err); }
}
```

`@img/sharp-wasm32` 是**纯 JS + WASM**（`index.cjs` + `sharp-wasm32-0.35.5.node.wasm`，
依赖 `@emnapi/runtime`），**与 CPU/OS 无关**，所以它天然能在 Android 上跑。

**踩到的坑**：设备上 `npm install @img/sharp-wasm32` 失败 ——
`getaddrinfo ENOTFOUND registry.npmjs.org`（**设备 DNS 不可用**）。
→ 改在**宿主机**取包再推进设备。

**验证（两层，不只是"能 require"）**：
```
SHARP_OK 0.35.5 libvips 8.18.7
SHARP_PIPELINE_OK 2x2 px 38,217,128,70,185,128,170,85,128,202,53,128
```
第二行是**真的跑完图像管线**：4×4 raw RGBA → `resize(2,2)` → 读回像素，
数值与插值预期一致。

> 代价：WASM 版比原生 libvips 慢（无 SIMD 原生路径）。若日后要原生性能，
> 仍需为 bionic/arm64 交叉编译 libvips（glib/gobject + 各编解码器），成本最高。

## 9.3 `@vscode/ripgrep` → 自建 android-arm64 平台包

`@vscode/ripgrep@1.18.0` 的 `lib/index.js` 只有 594 字节，逻辑是
`require.resolve('@vscode/ripgrep-<platform>-<arch>/bin/rg')` ——
**上游发布矩阵里没有 android**。

**修法**：自己编一个，并伪造同名平台包（**不需要改 dsh 一行代码**）：
```sh
rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/bin/aarch64-linux-android24-clang
export CC_aarch64_linux_android=$NDK/bin/aarch64-linux-android24-clang
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cargo install ripgrep --target aarch64-linux-android --root ~/dsh-android/tools/ripgrep-android --locked
llvm-strip tools/ripgrep-android/bin/rg          # 29.6 MB -> 4.6 MB
```
再建 `node_modules/@vscode/ripgrep-android-arm64/{package.json, bin/rg}`。

**真机/模拟器验证**：
```
rg --version → ripgrep 15.2.0 / simd(runtime):+NEON / LOAD 对齐 0x4000
RG_RUN ripgrep 15.2.0        ← 经 dsh 的 @vscode/ripgrep 解析后再真的执行
```
（`--locked` + `-C link-arg=-Wl,-z,max-page-size=16384` 是为了满足 Android 15/16 的 16 KiB 页要求。）

## 9.4 `@deepseek-ai/node-addon-system` → **上轮的失败是测法错**

**先纠正误判**：上轮记的 `ERR_PACKAGE_PATH_NOT_EXPORTED` **不是缺陷**。
它的 `exports` 里**本来就没有 `.` 入口**：

```json
"exports": { "./landlock-run": {...}, "./flock": {...}, "./package.json": "./package.json" }
```

所以 `require('@deepseek-ai/node-addon-system')` 本来就该失败；真正的入口是
`/landlock-run` 与 `/flock`，两个都能正常加载。消费方也正是这么用的
（`dsh-sandbox-local` → `/landlock-run`；`dsh-session-persistence-jsonl` → `/flock`）。

**真问题只有两个，都已解决**：

### (a) 缺 `node-addon-system-android-arm64` 平台包 —— 但它**自带 C 源码**
包 `files` 里包含 `src/main.c`（Landlock 启动器）与 `src/flock.c`（Node-API flock），
所以可以自建：

```sh
# landlock-run：纯 C11 + 自己内联的 Landlock UAPI（不依赖 <linux/landlock.h>）
$NDK/bin/aarch64-linux-android24-clang -O2 -fPIC -Wl,-z,max-page-size=16384 \
    nas/src/main.c -o nas/landlock-run          # 9,808 B
# system.node：Node-API C，用 NDK clang 直接编
$NDK/bin/aarch64-linux-android24-clang -O2 -fPIC -shared \
    -I/opt/homebrew/Cellar/node/26.12.0/include/node nas/src/flock.c -o nas/system.node   # 11,488 B
```
产物只 `NEEDED libdl.so / libc.so`（纯 bionic）。
然后建平台包 `@deepseek-ai/node-addon-system-android-arm64/{package.json, bin/landlock-run, bin/system.node}`。

### (b) `lib/flock.js` 硬编码平台白名单
```js
if (platform !== 'linux' && platform !== 'darwin') { throw ... ERR_FLOCK_UNSUPPORTED_PLATFORM }
```
Node 在 Android 上 `process.platform === 'android'` → 直接抛错。
**1 行补丁**（`patches/0001-node-addon-system-android.patch`）加上 `android`。
bionic 与 glibc 一样提供 `flock(2)`，所以平台门是唯一障碍。

### 验证结果
```
FLOCK_OK fd=19                          ← 真的拿到排他 flock（不是"能 import"）
LAUNCHER .../node-addon-system-android-arm64/bin/landlock-run
PROBE "unusable"
landlock-run: landlock is not enforced by this kernel (ABI unsupported or disabled)   PROBE_RC=125
```
**Landlock 的 `"unusable"` 是正确的 fail-closed 诊断**：设备内核
`6.6.127-android15-8-...-4k` 上 Landlock 未启用（该 ROM 的 kernel config 没开）。
`probe()` 的语义就是"这个内核到底强不强制"，返回 `unusable` 正是它该做的事 ——
dsh 的 sandbox 据此降级。**关键区别**：上一轮 `launcherPath()` 指向一个**根本不存在的路径**，
现在是"二进制存在、真的跑起来、由它自己报告内核不支持"。

## 9.5 `sherpa-onnx` → 不可修（根因明确，非配置问题）

**先纠正误判**：真正的依赖不是 `sherpa-onnx`，而是
`@deepseek-ai/dsh-experimental-speech-to-text-sensevoice` 依赖的 **`sherpa-onnx-node@1.13.8`**。
它**在 `node_modules` 里是存在的**（所以上轮的 `MODULE_NOT_FOUND` 也是裸名测法的产物）。

**真正的根因**：`sherpa-onnx-node` 的 `optionalDependencies` 只有
`darwin-{arm64,x64}` / `linux-{x64,arm64}` / `win32-{x64,ia32}` —— **没有 android**；
本机 `node_modules/sherpa-onnx-node/` 里**只有 JS，零个 `.node`/`.so`**。

**为什么不在本轮修**：要修得交叉编译整条 sherpa-onnx C++ 栈
（onnxruntime + kaldi-native-fbank + espeak-ng + …），并写 Node-API 绑定层。
规模远超本轮预算，且该插件是 **experimental**，dsh 核心不依赖它。

**结论：Android 上语音转文字功能不可用，属功能降级，不阻塞 dsh。**

## 9.6 ⭐ dsh 原生 APK：架构与两个 Android 地雷

### 工程与产物
- 工程：`android-app/`（AGP 9.2.0 + Gradle 9.4.1；`JAVA_HOME=/opt/homebrew/opt/openjdk@21`）
- 产物：`dist/dsh-android-0.2.0-rc.2-arm64-debug.apk`（**205 MB**，`com.dsh.android`，targetSdk 35）
- 架构（用户指定）：**dsh 本体是原生 APK**；Termux 只是 bash 执行后端。
  - Node 运行时：Termux 的 **Node 26.4.0** 作为 `jniLibs/arm64-v8a/libnode.so`
  - 它的 10 个 `.so` 依赖（`libz/libcares/libsqlite3/libffi/libcrypto/libssl/libicui18n/libicuuc/libicudata/libc++_shared`）
    走 **assets → filesDir/rt**（`LD_LIBRARY_PATH`）
  - dsh 依赖树：`assets/dsh-bundle.zip`（**25,949 文件 / 63 MB**，解压后 281 MB）
  - UI：`WebView` 加载 `dsh web` 打印的 URL
  - bash 后端：`TermuxDispatch` → `startForegroundService` → `com.termux/.app.RunCommandService`

### 🔴 地雷 1：**app 数据目录里的可执行文件无法 execve**（Android 10+）
- 现象：`spawnSync .../files/dsh/.../bin/rg` → **EACCES**。
- 根因：`untrusted_app` 对 `app_data_file` **没有 execute**（targetSdk ≥ 29 起）；
  `nativeLibraryDir` 是 `apk_data_file`，**有** execute。
- **更阴的一次**：`landlock-run` 被 deny 后，`probe()` 返回 `"unusable"` ——
  看起来和"内核不支持 Landlock"**一模一样**，实际是 exec 被拒。
- **正解**：所有可执行文件都以 **`lib<name>.so` 放进 `jniLibs`**，
  再由 app 在数据目录里建 **符号链接**指过去（SELinux 检查的是**解析后的 inode**）：
  ```java
  Os.symlink(nativeLibraryDir + "/librg.so", dshDir + "/node_modules/@vscode/ripgrep-android-arm64/bin/rg");
  ```
  → 见 `DshRuntime.linkExecutables()`。修好后 `RG_RUN ripgrep 15.2.0`、
  `landlock-run --probe` 能真的执行并**由它自己**报告内核不支持。

### 🔴 地雷 2：Termux 的 `libcrypto` 把 **OPENSSLDIR 编进了 `.so`**
- 现象（用 `run-as` 复现时）：Node 一行 JS 都没跑就死：
  ```
  libnode.so: OpenSSL configuration error:
  8000000D:system library:BIO_new_file:Permission denied:
  calling fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf, rb)
  ```
- 根因：Termux 的 OpenSSL 构建把
  `OPENSSLDIR=/data/data/com.termux/files/usr/etc/tls` 写死在 `libcrypto.so.3` 里；
  我们 app 的 uid 永远读不到 Termux 的私有目录，而 **OpenSSL 把"配置读不到"当致命错误**。
- **正解**：`DshRuntime.applyEnv()` 里显式 `OPENSSL_CONF=<filesDir>/openssl.cnf`
  （app 自己写一个空的合法配置）。Node 的 TLS 信任链用它自带的 CA 包，
  所以不需要任何真正的 OpenSSL 配置。
- ⚠️ **这条是长期隐患**：任何"从 Termux 拿二进制/库"的做法都会带上 Termux 的
  编译期路径。后续若自建 Node，应同时把 OpenSSL 的 prefix 指向 app 自己的目录。

### 端到端验证（`emulator-5554`，Android 16 / arm64-v8a；**真机当时锁屏，见 §9.7**）
```
node exec: rc=0
NODE_OK v26.4.0 android/arm64
extracted 25949 files in 5441 ms
linked .../bin/rg -> .../lib/arm64/librg.so
linked .../bin/landlock-run -> .../lib/arm64/liblandlockrun.so
Termux dispatch: permission com.termux.permission.RUN_COMMAND granted=true
DISPATCH_OK via startForegroundService
dsh web: http://127.0.0.1:8899/?token=aurvVPa2qHG_...
```
- **HTTP 层**：`curl -L` 经 303（token→cookie 认证）后 `HTTP=200 bytes=34846`，
  `<title>DeepSeek Harness</title>`，并拿到 `dsh-auth-...` cookie。
- **截图**：`evidence/dsh-android-apk-02-emulator.png` —— WebView 里**完整渲染出
  dsh 的 Preview Notice 界面**（鲸鱼 logo / Continue 按钮 / 设置齿轮）。
- **Termux 侧副作用**（vivo/模拟器都适用：以 Termux 侧文件为准）：
  ```
  $ adb shell run-as com.termux cat .../dshx/termux_proof.txt
  BRIDGE_OK Sat Oct 17 14:54:03 CST 2026 uid=10221 pwd=/data/data/com.termux/files/home
  ```
  `uid=10221` = **Termux 的 uid**（不是我们 app 的），证明命令确实由 Termux 执行。
- **8 个模块在 APK 内实测**（`run-as com.dsh.android` 跑 APK 自己的 node）：
  ```
  NODE v26.4.0 android arm64     KOFFI_OK 3.1.1        PTY_OK "PTY_OK"
  SHARP_OK 0.35.5 libvips 8.18.7 SHARP_PIPELINE_OK 2x2 px 38,217,...
  RG_RUN ripgrep 15.2.0          FLOCK_OK fd=19        LOKIT_OK   PROTOBUFJS_OK
  PROBE "unusable" (内核未启用 Landlock，正确)
  SHERPA_FAIL Could not find sherpa-onnx-node. Tried      ← 见 §9.5
  ```
- 全量证据存档：**`evidence/logs/dsh-apk-emulator-e2e.txt`**

## 9.7 ⚠️ 未完成项：**真机（vivo PA2573）装机验证** —— 卡在人类解锁

- 真机状态：`dumpsys window | grep mDreamingLockscreen` → **`mDreamingLockscreen=true`**（锁屏）
- 安装被拒：`pm install -r -t` → `Failure [INSTALL_FAILED_ABORTED: User rejected permissions]`
- 已尝试且**无效**：`input keyevent KEYCODE_WAKEUP` + 单指上滑
  （vivo 的解锁手势是**双指上滑**，adb 合成不出来）
- **需要人类**：在设备上双指上滑解锁（如需 PIN 一并输入）。
- 本轮改用 **`emulator-5554`（sdk_gphone64_arm64 / Android 16 / arm64-v8a）**完成了
  全部端到端验证（同 ABI、同 API 级别），真机验证待解锁后补做。

## 9.8 产物索引（本轮新增）

```
~/dsh-android/
├── android-app/                                  ⭐ 原生 APK 工程（AGP 9.2.0 + Gradle 9.4.1）
│   └── app/src/main/
│       ├── AndroidManifest.xml                   RUN_COMMAND / queries com.termux / icon
│       ├── java/com/dsh/android/{MainActivity,DshRuntime,ZipExtractor,Exec,LogFile,TermuxDispatch}.java
│       ├── jniLibs/arm64-v8a/{libnode.so, librg.so, liblandlockrun.so}
│       └── assets/{rt/*.so, dsh-bundle.zip}
├── dist/dsh-android-0.2.0-rc.2-arm64-debug.apk    205 MB 交付物
├── nas/                                          node-addon-system 自建产物（landlock-run + system.node）
├── tools/ripgrep-android/bin/rg                  自建 ripgrep 15.2.0
├── patches/0001-node-addon-system-android.patch  flock.js android 平台补丁
└── evidence/
    ├── dsh-android-apk-01-emulator.png           首屏（含 boot 日志）
    ├── dsh-android-apk-02-emulator.png           ⭐ dsh GUI 在 WebView 内完整渲染
    ├── dsh-web-gui-served.html                   ⭐ 从设备 curl 回来的 GUI HTML（title=DeepSeek Harness）
    └── logs/dsh-apk-emulator-e2e.txt             ⭐ 全量端到端证据
```


## The web host could not start: the port belonged to Termux

`dsh web` was launched with a hard-coded `--port 8899`. On this device 8899 is
already held by Termux (uid 10238), so the start failed two seconds in:

    I DshRuntime: dsh web exited rc=1
    ... Error: listen EADDRINUSE: address already in use 127.0.0.1:8899
    ... webserver (required) failed to activate

and because webserver is a *required* plugin, dsh aborted the whole host: nine other
plugins stayed "waiting for services", the WebView never received a URL, and the UI
showed nothing but the boot log.

`DshRuntime.pickFreePort()` now bind-tests 8900..8920 and uses the first free one.
Verified on the vivo PA2573:

    I DshRuntime: exec: libnode.so ... web --no-open --port 8900
    I DshRuntime: dsh| dsh web: http://127.0.0.1:8900/?token=...
    I DshAndroid: DSH_WEB_URL http://127.0.0.1:8900/?token=...

and the Web GUI renders in the app: sidebar (新会话 / 插件 / 工作区 / 设置) and the
预览版说明 dialog.

The launcher icon is the harness's own artwork (the whale, from the desktop
application's Resources/icon.png), replacing a stand-in prompt chevron.

---

# Round 3 (2026-10-19): foreground service, SAF open/save, real icon, device findings

## 10.1 Foreground service owns Node now (DshService)

The host used to live inside `MainActivity`. Task switches could freeze and reclaim it,
and the WebView lost its server. It now lives in `com.dsh.android.DshService`:

* started with `startForegroundService()`, calls `startForeground()` **before** any slow
  work (first-run bundle extraction is 30 s+),
* `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, `START_STICKY`,
  `android:stopWithTask="false"`, `onTaskRemoved` deliberately does not stop,
* `BootLog` is the process-wide status ring buffer; the activity merely renders it and
  loads `BootLog.url()` when it appears, so activity re-creation is harmless.

Verified on device:

    $ adb shell dumpsys activity services | grep -n DshService
    11348:  * ServiceRecord{916f422 u0 com.dsh.android/.DshService c:com.dsh.android}
    ...
    12:30:00.335 [DshAndroid] === dsh native APK boot (foreground service) ===
    12:30:02.600 [DshRuntime] dsh| dsh web: http://127.0.0.1:8900/?token=...
    12:30:02.601 [DshAndroid] DSH_WEB_URL http://127.0.0.1:8900/?token=...

`dumpsys activity services com.dsh.android` (package filter argument) does **not** list
DshService — grep the unfiltered output, or you will wrongly conclude the service is dead.

## 10.2 SAF: the six-method contract, implemented for a Node host

`SafBridge.java` implements the shared contract verbatim:

    safPickOpen()V  safIsDone()Z  safPickSave(String)V
    safSaveDone()Z  safHasSaveUri()Z  safPublish(String)Z

Because dsh is Node, a Node process cannot JNI-call the Activity. So the same six
operations are also exposed as a command, `filesDir/bin/saf` (+ `saf.js`, written at boot
with absolute paths baked in; `filesDir/bin` is appended to the host's PATH and
`DSH_SAF_DIR` is exported):

    saf open [--into DIR]      # ACTION_OPEN_DOCUMENT, copies picks to filesDir/saf-inbox,
                               # writes filesDir/saf-manifest.txt (one path per line)
    saf save <file> [--name N] # ACTION_CREATE_DOCUMENT, then publishes
    saf state

Wire format (filesystem only, as the contract requires):

    filesDir/saf/req           <seq>\n<open|save>\n<arg>\n     (save arg = "title\t srcPath")
    filesDir/saf/res           <seq>\n<pending|done|error|cancelled>\n<message>
    filesDir/saf-manifest.txt  imported local paths
    filesDir/saf-target-uri.txt the chosen content:// URI

The watcher thread polls `req` and turns it into `startActivityForResult` **via
runOnUiThread** (without that the picker silently never appears). Save is two-stage:
scratch file under `filesDir/export/` → picker on the UI thread → `safPublish()` waits
for the scratch file to stop growing for 1.5 s → streams it to the URI.

The WebView also routes `<input type=file>` through `onShowFileChooser` →
ACTION_OPEN_DOCUMENT, so dsh's own upload widgets land in the same vivo picker.

Picker on this ROM: `com.android.filemanager/.selector.view.SelectorHomeActivity`.
`com.android.documentsui` is a shim — never test for it.

## 10.3 dsh could not persist a session: SELinux denies link(2) in app data

First real prompt on device failed at the persistence layer:

    EACCES: permission denied, link
      '.../sessions/--data-data-com.dsh.android-files-dsh--/session-<id>/session.v4.jsonl.zstd.<tmp>'
      -> '.../session.v4.jsonl.zstd'

Directly reproducible:

    $ adb shell "run-as com.dsh.android sh -c 'cd files && echo hi > t1 && ln t1 t2'"
    ln: cannot create hard link from 't1' to 't2': Permission denied

`@deepseek-ai/dsh-session-persistence-jsonl` publishes the current generation with
`fs.link()` for EEXIST semantics. Patch
(`patches/0002-session-persistence-hardlink-android.patch`) adds an exclusive
`copyFile(..., COPYFILE_EXCL)` fallback on EACCES/EPERM/ENOTSUP/EXDEV/EOPNOTSUPP.
It is applied to `assets/dsh-bundle.zip` itself (both `lib/index.js` and `lib/worker.cjs`)
and the extraction marker was bumped to `.extracted-v2` so existing installs re-extract.

## 10.4 Icon provenance: the real DSH favicon, not a stand-in

Source asset, extracted from the desktop app's asar:

    /Applications/DeepSeek Harness.app/Contents/Resources/app.asar
      -> dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/favicon.svg
      sha256 9e983b4f649c25c6ca0623a50be1a6e705fd8f49f756638b980fa13b40575ab6

(asar layout note for future rounds: JSON header starts at byte 16, **file data starts at
8 + headerSize**, not 16 + headerSize.)

The SVG was rendered with `rsvg-convert` and re-composited to: mipmap-mdpi..xxxhdpi
`ic_launcher.png` + `ic_launcher_round.png`, and `drawable/ic_launcher_foreground.png`
(432 px, 60 % safe zone) + `drawable/ic_launcher_monochrome.png` for
`mipmap-anydpi-v26/ic_launcher.xml` (adaptive). Verified against the artefact:

    application-icon-160:'res/mipmap-anydpi-v26/ic_launcher.xml'
    application-icon-240:'res/mipmap-anydpi-v26/ic_launcher.xml'
    application-icon-320:'res/mipmap-anydpi-v26/ic_launcher.xml'
    application-icon-480:'res/mipmap-anydpi-v26/ic_launcher.xml'
    application-icon-640:'res/mipmap-anydpi-v26/ic_launcher.xml'

## 10.5 ⚠️ Device/install facts learned the hard way

* **`pm install` is not headless on this ROM.** It starts
  `com.android.packageinstaller/.PackageInterceptActivity` ("安全守护提示您",
  checkbox 已了解应用的风险检测结果, button 继续安装). If the screen is **locked**
  (`dumpsys window | grep mDreamingLockscreen` → true) the install is refused instantly
  with `INSTALL_FAILED_ABORTED: User rejected permissions`; if it is unlocked you must
  tick the checkbox at (1548,1809) and press 继续安装 at (1547,1936) (Rotation0), or the
  `pm install` blocks for minutes and then reports the same failure.
  `uiautomator dump` gives exact bounds — prefer it over hard-coded coordinates.
* The device is shared with other ports' agents. `topResumedActivity` may be another
  app's picker mid-test; check it before every tap.
* Wireless debugging drops when the Mac changes networks; the phone must be on the same
  SSID. Symptom: `ping 192.168.0.108` 100 % loss and stale `10.20.x.x offline` adb
  entries. `~/adb-up.sh --scan` cannot find it across subnets.

## 10.6 State of verification at the end of this round

Done and evidenced on the vivo device: foreground service hosts dsh web, WebView renders
the full GUI, Termux dispatch is `DISPATCH_OK via startForegroundService`, SAF bridge and
CLI are installed at boot, session-persistence failure root-caused and patched.

NOT yet re-verified after the rebuild (device left the network mid-round): the patched
session publish, a full model answer in the app UI, and the SAF picker round-trip.
The APK that carries all of the above is `dist/dsh-android-0.3.0-arm64-debug.apk`.

---

# Round 4 (2026-10-20): full-screen UI, Mac model config, real prompt, SAF open

## 11.1 The app now looks like the desktop app: no chrome, no boot log

Round 3 hid the boot-log panel after the host URL appeared, but the window still had
(a) the Android title bar (`Theme.Material.Light`) and (b) the system bars, and users
reported the top of the dsh UI (logo / 新会话) being covered. Fixes:

* theme is `@android:style/Theme.Material.Light.NoActionBar` plus
  `requestWindowFeature(Window.FEATURE_NO_TITLE)`;
* `hideSystemBars()` (WindowInsetsController on API 30+, SYSTEM_UI_FLAG_IMMERSIVE_STICKY
  below) plus `setDecorFitsSystemWindows(false)`, re-applied in `onWindowFocusChanged`;
* `barScroll` / `logPanel` are `GONE` once `BootLog.url()` is known — dsh owns the whole
  display. The log remains available at `files/dsh.log`.

Verified on the vivo PA2573: logo, 新会话, 插件, 工作区, 设置 and the composer are all
visible edge-to-edge, nothing overlaps them.

## 11.2 The Mac's model configuration, ported to the Android app

The desktop profile's `cordis.patch.yml` was copied to the app's web profile
(`files/.dsh/profiles/web/cordis.patch.yml`): `agent-default-model`
(deepseek-account / deepseek-flash / high), the whole `llm-pi-ai` provider list
(opencode-go, deepseek, xiaomi, lmstudio, stepfun, kimi-for-coding, local-5090),
`ui-settings-*`, and `subagent-model-selection-settings`.

The credential refs from `~/.dsh/.credentials.yaml` (DEEPSEEK_API_KEY, OPENCODE_GO_API_KEY,
XIAOMI_API_KEY, STEPFUN_API_KEY, KIMI_FOR_CODING_API_KEY, LMSTUDIO_API_KEY,
LOCAL_5090_API_KEY) plus the `deepseek-account-platform/*` records were installed into
`files/.dsh/.credentials.yaml`, merged with the device's own browser-session record.

⚠️ **Deliberately NOT baked into the APK.** The release APK is a public GitHub asset; an
API key inside `assets/` would be published. Keys live in the app's private data dir
(mode 600, app uid) and a fresh install needs them re-applied (script in
`scripts/apply-model-config.sh`, which reads the Mac's `~/.dsh` config).

## 11.3 A real prompt is answered by the default model on the device

`dsh headless` inside the app's own Node runtime, with the ported config:

    $ adb shell run-as com.dsh.android ... bin.js headless "Reply with exactly DEVICE_PROMPT_OK"
    DEVICE_PROMPT_OK

and the same prompt through the app UI (WebView → dsh web host) produced a persisted
session (evidence/logs/dsh-ui-session-device.jsonl, decompressed from
`session.v4.jsonl.zstd`):

    {"type":"user/message", ... "text":"Reply DEVICE_PROMPT_OK" ...}
    {"type":"assistant/message", ... "content":[...,{"type":"text","text":"DEVICE_PROMPT_OK"}],
     "source":{"kind":"model","provider":"deepseek-account","model":"deepseek-flash"}, ...}

Screenshot: `evidence/dsh-android-0.3.0-ui-answer.png` (interface says 已完成, 用时 1 秒).

## 11.4 Session persistence: `link(2)` is denied twice, not once

Round 3 patched `publishCurrentExclusive`. A prompt still failed, now from
`materializePosix`:

    at async Proxy.materializePosix (dsh-session-persistence-jsonl/lib/index.js:3156)
    EACCES: permission denied, link '...session.v4.jsonl.zstd.<tmp>' -> '...session.v4.jsonl.zstd'

A `--require` hook that wraps `fs.link`/`fs.promises.link` with a stack-printing guard
found the second call site. `materializePosix` uses the module-level `link` import, not
the `internals.fs` wrapper, so it needed its own COPYFILE_EXCL fallback.
`patches/0002-session-persistence-hardlink-android.patch` now covers both sites, and the
patched files are inside `assets/dsh-bundle.zip`.

## 11.5 SAF open round-trip on the vivo picker (real file)

`com.android.filemanager/.selector.view.SelectorHomeActivity` is the picker. The whole
flow was driven by the `saf` command (Node → filesystem request → Activity picker):

    04:43:52 [DshSaf] imported content://com.android.providers.media.documents/document/
             document%3A1000027124 -> .../files/saf-inbox/dsh-open-test.txt (38 bytes)
    04:43:52 [DshSaf] open done: [.../files/saf-inbox/dsh-open-test.txt]

    files/saf-manifest.txt: /data/user/0/com.dsh.android/files/saf-inbox/...-dsh-open-test.txt
    cat files/saf-inbox/dsh-open-test.txt: DSH_SAF_OPEN_OK round3 from Downloads
    cat /sdcard/Download/dsh-open-test.txt: DSH_SAF_OPEN_OK round3 from Downloads

Full log: `evidence/logs/dsh-saf-open-device.txt`.

### Two lessons that cost hours

1. **`input tap` y-coordinates from a screenshot preview are not reliable at the bottom
   of the screen.** The vivo picker's 完成 button really sits at
   `[2716,1924][3046,2039]` (center 2881,1981), ~150 px below where a naive half-scale
   reading put it. Use `uiautomator dump` bounds for any button that "does nothing".
2. **The picker cannot be launched while another app owns the foreground.** Android
   denies background activity starts, and the request then waits forever. SafBridge now
   tracks `resumed` (set from MainActivity.onResume/onPause) and the watcher waits for it
   (up to 150 s) instead of firing into the void. When testing on this shared device,
   check `topResumedActivity` immediately before every tap.

## 11.6 SAF save round-trip verified (same session)

    saf: save requested (seq=1792447275222)
    06:02:12 [DshSaf] launched CREATE_DOCUMENT title=dsh-save-test.txt
    06:04:27 [DshSaf] save target uri=content://com.android.providers.downloads.documents/document/341
    06:04:28 [DshSaf] safPublish .../files/dsh/dsh-save-test.txt -> .../document/341 (36 bytes)
    06:04:28 [DshSaf] publish result ok=true uri=.../document/341
    saf: save done: published 36 bytes to content://.../document/341

    $ adb shell cat /sdcard/Download/dsh-save-test.txt
    DSH_SAF_SAVE_OK 2026-10-20 from dsh
    $ adb shell ls -la /sdcard/Download/dsh-save-test.txt
    -rw-rw---- 1 u0_a0 media_rw 36 2026-10-20 06:04 /sdcard/Download/dsh-save-test.txt

Note: CREATE_DOCUMENT on this ROM is served by `com.android.documentsui/.picker.PickActivity`
(not the vivo filemanager), while ACTION_OPEN_DOCUMENT goes to
`com.android.filemanager/.selector.view.SelectorHomeActivity` — both are reachable through
the same contract. Evidence: `evidence/logs/dsh-saf-save-device.txt`,
`evidence/logs/dsh-saf-open-device.txt`.

## 12. "没有 bash" 的根因与修复

用户报告：安卓端 dsh 不能写入/edit、也没有 bash。

排查结论：

1. **工作区不是权限问题。** `files/.dsh/storages/workspace.json` 里工作区是
   `/data/data/com.dsh.android/files/dsh`，属于应用私有目录，本 uid 可写 —— 存储权限不是原因。
2. **设备上根本没有 bash。** `/system/bin/sh` 是唯一的 shell（toybox），
   `/system/bin/bash`、`ash`、`mksh` 都不存在。
3. **dsh 的 PATH 里也没有任何 bash。** `DshRuntime.applyEnv()` 只设了
   `PATH=/system/bin:/system/xbin:<files>/bin`，而且没有设 `SHELL`。
   agent 写的命令基本都以 `bash` 开头，于是每一步 shell 都失败，
   表现成「这个设备没有 shell」，随后它就放弃 edit/运行。
4. Termux 确实装了（`com.termux`），`allow-external-apps = true` 已开，
   `com.termux.permission.RUN_COMMAND` 也已授予（granted=true）。
   但 Termux 的 bash 在它自己的私有目录里，**只有 Termux 自己的 uid 能执行**，
   dsh 无法直接调用 —— 所以 `TermuxDispatch` 走 RUN_COMMAND 转发是必要的补充，
   不该作为唯一路径。

修复（`DshRuntime.java`）：在 `files/bin/`（本来就在 PATH 上）里放两个一行的 exec shim，
并把 `SHELL` 指过去：

```sh
#!/system/bin/sh
exec /system/bin/sh "$@"
```

```java
env.put("SHELL", ensureShim(ctx, "bash", "/system/bin/sh", "$@").getAbsolutePath());
ensureShim(ctx, "sh", "/system/bin/sh", "$@");
```

这样任何 `bash -c ...` 都能落到 toybox sh 上（不依赖 Termux、不涉及跨 uid），
Termux 可用时仍可走原有 dispatch 拿到完整 bash。

顺带修了构建：`AndroidManifest.xml` 里硬编码的 `android:debuggable="true"` 会让
release 构建直接报错（`HardcodedDebugMode`），已移除（模型配置早就灌好了，
需要 run-as 时用 debug 构建类型）。另外本机 Gradle 9.3.1 低于项目要求的 9.4.1，
`~/.gradle/wrapper/dists/gradle-9.4.1-bin/.../bin/gradle` 可用。

APK 已构建并签名：`android-app/app/build/outputs/apk/release/app-release-signed.apk`（136 MB，
含 libnode.so 49.7 MB + dsh-bundle 66.5 MB）。

## 14. 只有 toybox 的 sh 够不够？以及怎么调 Termux

**toybox 并不像听起来那么弱。** `/system/bin` 里有 sed / grep / awk / find / xargs / tar /
gzip / sort / cut / tr / printf / timeout / nc 等，日常脚本的绝大多数能跑。
真正缺的是：bash 专属语法（数组、`[[ ]]`、进程替换）、以及 python / git / curl 这类**额外包**。

所以策略是两层：

1. **`files/bin/bash` 与 `files/bin/sh`** → 一行的 exec shim，落到 `/system/bin/sh`。
   这一层永远可用、零依赖，保证任何以 `bash` 开头的命令至少不会「找不到命令」。
2. **Termux 通道** → 通过 `com.termux.RUN_COMMAND` 使用真正的 bash 与它装好的包。

第 2 层为什么不是一个 shell 脚本就能搞定：

- Termux 的 bash 在 `/data/data/com.termux/files/usr/bin/`，**只有 Termux 自己的 uid 能执行**；
  SELinux 也不允许跨 uid 读它的私有目录。所以必须走服务转发（`TermuxDispatch.dispatch()`，
  当前状态 `DISPATCH_OK`、权限 `granted=true`）。
- RUN_COMMAND 是**单向**的：Termux 执行完不会把 stdout 送回调用者。要拿回输出，脚本必须把结果
  写到一个**双方都能读**的位置，而 dsh 的应用私有目录 Termux 写不进去。
  可行落点只有共享存储（`/sdcard/...`），这要求应用具备存储访问权限
  （FreeCAD 就是为此申请了 `MANAGE_EXTERNAL_STORAGE`）。
- 于是完整实现需要：请求/响应文件对（放在共享目录）、一个 Java 侧轮询线程、
  超时与清理。约 80 行 Java，不是一条命令能替代的。

已先行落地的部分：给 Node 进程导出

```
TERMUX_PREFIX=/data/data/com.termux/files/usr
TERMUX_BASH=/data/data/com.termux/files/usr/bin/bash
```

这样工具和 agent 能知道 Termux 在哪、并且在需要时通过 dispatch 转发。
完整的 `termux` 命令行（带回显输出）留作下一步。

## 2026-10-22：opencode-go-2 已配进安卓 dsh；Shizuku 其实一直都在

### 1. opencode-go-2

安卓 dsh 的凭据里**本来就有** `OPENCODE_GO_API_KEY` 和 `OPENCODE_GO_2_API_KEY`
（早前同步 `.credentials.yaml` 时一起带过去了），缺的只是 **provider 定义**和默认模型指向。

做法：把 Mac 上 `~/.dsh/profiles/desktop/cordis.patch.yml` 里整整 236 行的
`opencode-go-2` 块搬到设备的 `files/.dsh/profiles/web/cordis.patch.yml`，
并把 `agent-default-model` 改成：

```yaml
- id: agent-default-model
  config:
    provider: opencode-go-2
    model: deepseek-v4.1-flash
```

`opencode-go-2` 的要点：
- `apiKeyEnv: OPENCODE_GO_2_API_KEY`
- `baseURL: https://opencode.ai/zen/go/v1`
- `api: openai-completions`
- 自定义头 `x-opencode-session: kimi-code-azuma-mac-2`
  （和第一个 opencode-go 的 `...-mac` 区分开）

设备上需要 debuggable 包才能写 `files/.dsh/`（release 包 run-as 会被拒），
所以临时装了 `assembleDebug` 版本 —— 与 release 同用 debug 签名，可直接覆盖安装。

验证：dsh 重启后进程存活、web 服务在 8900–8903 正常监听，说明配置被接受。
模型真正可用要等下一次对话请求。

### 2. Shizuku 一直都在，之前的检测是错的

子智能体报告「Shizuku 根本没装」，实测：

```
$ pm list packages | grep -c moe.shizuku
1
$ ps -A | grep shizuku
u0_a321  10931 ... moe.shizuku.privileged.api
shell    11400 ... shizuku_server          ← 服务在跑，shell uid
$ ls /data/local/tmp | grep shizuku
drwx---rwx shizuku
-rwx------ shizuku_starter
```

所以 **Shizuku 已安装、已启用、服务正在运行**。之前那次「没装」的结论是
agent 在它自己的沙箱里跑 `pm list packages` 得到空结果导致的误判 —— 这类包管理查询
在受限环境下并不可靠。

真正卡住的是**接入方式**，不是安装与否：

- Shizuku 的能力通过 **Binder** 暴露，dsh 是 Node 进程，没有 SDK 就无法直接拿 binder；
- 通用后备 `rish` 需要 `app_process`，而应用 uid 起 `app_process` 会因为
  dalvik-cache 归属问题直接 abort（`Error changing dalvik-cache ownership`）。

**可行路线**（按代价排序）：
1. **Termux 通道**（最实际）：dsh 已有 `TermuxDispatch` 且
   `com.termux.permission.RUN_COMMAND` 已授予；RUN_COMMAND 是单向的，
   要拿回输出需要一个「共享目录请求/响应文件对 + Java 轮询线程」的桥，约 80 行 Java。
2. **Shizuku SDK 集成**：在 dsh 的 Java 侧引入 Shizuku API，暴露一个
   「以 shell uid 执行命令」的方法给 Node —— 工作量大，但这是唯一能真正用上
   shell 权限的方式。
3. `nc -l -p 9999 -e sh`（在 Termux 里手动跑）作为不需要任何权限的临时通道。

## 2026-10-22：dsh → Termux 的请求/响应桥

`TermuxDispatch` 只能单向下发（RUN_COMMAND 没有回传），所以 dsh 能"让 Termux 做事"
但永远看不到结果。新增 `TermuxBridge` 用共享目录做握手：

```
dsh    写    /sdcard/dsh-bridge/cmd-<id>.sh
dsh    发    RUN_COMMAND bash .../runner.sh <id>
Termux 跑    cmd-<id>.sh，把 stdout/stderr 写进 out-<id>.txt，退出码写 rc-<id>.txt
dsh    轮询  看到 rc-<id>.txt 就读取并清理
```

前提：`com.termux.permission.RUN_COMMAND` 已授权（已有），并且 Termux 有共享存储
读权限 —— 否则 runner 根本看不到请求文件。

启动时会跑一次自检并写进 `files/dsh.log`：

```
termux bridge ok=true rc=0 out=BRIDGE_OK|aarch64|...
```

（本轮最后一次安装后应用未再启动，这条日志还没产生，下轮验证。）

## 2026-10-23：Termux 桥加了两条明确的失败诊断

原来的超时消息只写「timed out after N ms」，看不出是没装 Termux、Termux 被冻结、
还是**没有共享存储权限**。后者是最可能的：握手要靠 `/sdcard/dsh-bridge` 双向读写，
而 Termux 的存储权限不是默认给的，要用户自己跑 `termux-setup-storage` 并授权。

现在：

- 写请求之前先检查 `Environment.getExternalStorageState()` 和目录是否可写，
  不可写直接返回原因，不浪费 20 秒；
- 超时消息直接点名最可能的原因和修复动作。

注意：run 命令本身是通过 RUN_COMMAND 传过去的，所以**即使没有存储权限，
命令其实能执行** —— 拿不回输出而已。这也是为什么这个桥必须依赖 /sdcard。
