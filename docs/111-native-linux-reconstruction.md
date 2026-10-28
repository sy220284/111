# Linux N-API 高级语言替代实现（可选，不接管默认运行）

本目录来自先前 2.1.310 原生重建工程。它包含可实际编译的 **C** 源码：

- `native/linux/bun_ant_linux_napi.c`：Unix Socket `SO_PEERCRED` PID/UID、`ttyname_r`、进程 `prctl(PR_SET_DUMPABLE)` 和控制终端操作。
- `native/linux/audio_capture_napi.c`：ALSA PCM 录音/播放，N-API 线程安全回调；设备及并发行为仍需真实硬件验证。
- `native/linux/clipboard_napi.c`：X11 CLIPBOARD / PRIMARY N-API 替代层，异步读取；没有实现 Wayland 原生 compositor 协议。

以上均为根据功能接口独立实现的**替代代码**，并非原始 Rust、Zig、Bun/JSC 内部源码，也不宣称完全行为等价。

## 编译

Linux x64、Node 22 Node-API 头文件、C11 编译器、`libX11` 开发头与链接库、`libasound.so.2` 运行库：

```sh
make -C native/linux all
node --test tests/native-linux-reconstruction.test.cjs
```

默认输出到 `.generated/native-linux/`，**不会覆写** `resources/bunfs/*.node`。验证可直接在 GitHub Actions 的 ubuntu-latest Linux x64 上运行；没有 Xvfb 时图形环境专项测试明确跳过。组件不安装 npm 钩子，不自动注入任何全局对象。

## 验证限制与安全注意

- `getPeerPid`、`getPeerUid` 从独立子进程真实 Unix Socket 读取，错误文件描述符失败；`setDumpable` 仅在隔离测试子进程调用，以免改变主测试进程状态。
- `loginTty` 会修改**调用进程**的会话和 stdio，必须只在隔离终端子进程中调用。
- 原版音频模块的真实硬件设备、采样漂移、极端并发和设备断连路径未完全测试。不要在默认 CLI 中用新 `.node` 覆盖供应商二进制。
- Xvfb 是真实的隔离 X11 传输测试，不代表 Wayland/实际桌面环境已验收。
- GitHub CI 默认只在本地测试代码 ABI 和指定环境路径；原版 ELF 的完整业务等价尚未证明。