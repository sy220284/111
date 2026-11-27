package io.github.sy220284.codexandroid

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Owns running terminal/PTY commands and persistent stdout/stderr receipts. */
internal class TerminalTaskOperations(
    private val vm: CodexViewModel,
    private val scope: CoroutineScope
) {
    private val terminalSession get() = vm.terminalSession
    private val activeWorkspace get() = vm.activeWorkspace
    private val workspaceRoot get() = File(vm.workingDirectory).canonicalFile
    private val terminalTranscript get() = vm.terminalTranscript
    private val errors get() = vm.errors
    private val bridge get() = vm.bridge
    private var commandOwner: Pair<String, TaskRequestOwner>? = null
    private fun ownerFor(id: String): TaskRequestOwner? = commandOwner?.takeIf { it.first == id }?.second

    /** Terminal commands are always launched from an explicitly confirmed UI action. */
    fun startTerminalCommand(command: String, tty: Boolean = false, networkEnabled: Boolean = false) {
        if (vm.conversationState.value.remote != null) {
            errors.value = errors.value + ("terminal" to "当前为远端任务，请在对话中交给远端智能体执行命令")
            return
        }
        if (!vm.globalWorkSnapshot().canStartNewTask) {
            errors.value = errors.value + ("terminal" to
                "本机数据正在备份或恢复，完成后再执行终端命令")
            return
        }
        if (vm.connectionRestarter.busy.value) {
            errors.value = errors.value + ("terminal" to "正在重新连接内核，请稍后执行命令")
            return
        }
        if (terminalSession.value.running || vm.terminalStarting.value) {
            errors.value = errors.value + ("terminal" to "已有命令正在执行，请先停止或结束该命令")
            return
        }
        val cwd = try {
            WorkspaceSelectionContract.validate(workspaceRoot, File(activeWorkspace.value))
                .absolutePath
        } catch (error: Exception) {
            errors.value = errors.value + ("terminal" to
                (error.message ?: "任务项目目录不可用，请重新选择"))
            return
        }
        val owner = vm.captureTaskOwner()
        if (owner == null) { errors.value = errors.value + ("terminal" to "运行核心尚未连接"); return }
        val id = UUID.randomUUID().toString()
        val args = TerminalSessionContract.execute(command, cwd, id, tty, networkEnabled)
        // Own the pending start synchronously: a second tap or global backup
        // cannot overtake Android's asynchronous foreground-service creation.
        vm.terminalStarting.value = true
        commandOwner = id to owner
        scope.launch {
            try {
                CodexForegroundService.startAndAwaitReady(vm.getApplication())
                check(vm.globalWorkSnapshot().canStartNewTask) {
                    "备份或恢复正在进行，终端命令未启动"
                }
                owner.requireCurrent(vm)
                terminalSession.value = TerminalSessionState.start(id, command, tty)
                vm.terminalStarting.value = false
                errors.value = errors.value - "terminal"
                terminalTranscript.begin(id)
                val result = bridge.request("command/exec", args, timeoutMillis = null, expectedSession = owner.epoch, sendPermit = { owner.current(vm) })
                terminalTranscript.appendTail(id, result)
                val active = terminalSession.value
                if (active.processId == id) terminalSession.value = active.finish(result)
            } catch (failure: Exception) {
                val active = terminalSession.value
                if (active.processId == id && active.running) {
                    terminalSession.value = active.fail(
                        failure.message ?: "终端命令执行失败")
                } else {
                    errors.value = errors.value + ("terminal" to
                        ("终端未启动：" + (failure.message ?: "前台服务未就绪")))
                }
            } finally {
                if (commandOwner?.first == id) commandOwner = null
                vm.terminalStarting.value = false
            }
        }
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        val current = terminalSession.value
        if (!current.running || !current.tty) {
            errors.value = errors.value + ("terminalResize" to "请先启动交互式终端")
            return
        }
        val params = try {
            TerminalSessionContract.resize(current.processId, rows, cols)
        } catch (failure: IllegalArgumentException) {
            errors.value = errors.value + ("terminalResize" to
                (failure.message ?: "终端尺寸输入无效"))
            return
        }
        val owner = ownerFor(current.processId) ?: return
        scope.launch {
            try {
                owner.request(vm, "command/exec/resize", params)
                if (terminalSession.value.processId == current.processId)
                    errors.value = errors.value - "terminalResize"
            } catch (failure: Exception) {
                if (terminalSession.value.processId == current.processId)
                    errors.value = errors.value + ("terminalResize" to (failure.message ?: "终端尺寸调整失败"))
            }
        }
    }

    fun sendTerminalInput(line: String, afterSent: () -> Unit = {}) {
        val state = terminalSession.value
        if (!state.running || state.terminating) return
        val owner = ownerFor(state.processId) ?: return
        val args = TerminalSessionContract.write(state.processId, line)
        scope.launch {
            try {
                owner.request(vm, "command/exec/write", args)
                if (terminalSession.value.processId == state.processId) {
                    errors.value = errors.value - "terminalInput"
                    afterSent()
                }
            } catch (failure: Exception) {
                errors.value = errors.value + ("terminalInput" to
                    (failure.message ?: "终端输入失败"))
            }
        }
    }

    fun stopTerminalCommand() {
        val active = terminalSession.value
        if (!active.running || active.terminating) return
        val owner = ownerFor(active.processId) ?: return
        terminalSession.value = active.copy(terminating = true)
        scope.launch {
            try {
                bridge.request("command/exec/terminate",
                    TerminalSessionContract.terminate(active.processId), expectedSession = owner.epoch,
                    sendPermit = { terminalSession.value.processId == active.processId })
                errors.value = errors.value - "terminalStop"
            } catch (failure: Exception) {
                val current = terminalSession.value
                if (current.processId == active.processId && current.running) {
                    terminalSession.value = current.copy(terminating = false)
                    errors.value = errors.value + ("terminalStop" to
                        (failure.message ?: "无法停止终端命令"))
                }
            }
        }
    }


}
