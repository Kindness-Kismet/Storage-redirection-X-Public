package com.storage.redirect.x.data.service

import com.storage.redirect.x.util.Paths

object MonitorService {
    suspend fun query(before: Long, lineCount: Int): CommandResult =
        runCommand("monitor-query $before 0 $lineCount")

    suspend fun reloadPolicy(): Boolean = runCommand("control monitor-policy").isSuccess

    suspend fun clear(): Boolean = runCommand("control clear-monitor").isSuccess

    // 参数仅由本类的固定命令和数字构成。
    private suspend fun runCommand(arguments: String): CommandResult = RootService.runCommand(
        "for binary in ${Paths.MODULE_DIR}/bin/*/srx_logd; do " +
            "[ -x \"\$binary\" ] || continue; \"\$binary\" $arguments; exit \$?; done; exit 1"
    )
}
