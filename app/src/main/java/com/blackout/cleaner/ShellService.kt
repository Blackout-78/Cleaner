package com.blackout.cleaner

/** Runs inside Shizuku's own shell-privileged process, never inside the Cleaner app itself. */
class ShellService : IUserService.Stub() {
    override fun destroy() { System.exit(0) }

    override fun exec(command: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            "$out\n[exit $code]".trim()
        } catch (e: Exception) {
            "[error ${e.message}]"
        }
    }
}
