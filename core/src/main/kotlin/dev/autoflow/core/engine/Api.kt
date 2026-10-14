package dev.autoflow.core.engine

import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.expr.EvalEnv
import dev.autoflow.core.expr.Value
import dev.autoflow.core.model.Block
import dev.autoflow.core.model.FlowDoc
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** Runs one block and returns the id of the output port to follow, or null to end this path. */
typealias Executor = suspend (RunContext) -> String?

interface Clock {
    fun now(): Long
    val zone: ZoneId get() = ZoneId.systemDefault()

    companion object {
        val System = object : Clock {
            override fun now() = java.lang.System.currentTimeMillis()
        }
    }
}

/** What the engine needs from the platform. Android adds device access on top of this. */
interface Host {
    val clock: Clock get() = Clock.System

    /** Suspends until a wall-clock time. Android overrides this to wake the device for long waits. */
    suspend fun delayUntil(epochMs: Long) {
        val d = epochMs - clock.now()
        if (d > 0) delay(d)
    }
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val flowId: String,
    val runId: String,
    val time: Long,
    val level: LogLevel,
    val blockId: String?,
    val blockTitle: String?,
    val message: String,
    val durationMs: Long? = null,
)

fun interface LogSink {
    fun log(entry: LogEntry)
}

/** Everything a block's executor can use while it runs. */
interface RunContext {
    val flow: FlowDoc
    val block: Block
    val spec: BlockSpec
    val runId: String
    val host: Host
    val env: EvalEnv

    /** The param as stored (or its default). */
    fun raw(key: String): String

    /** The param with `{expression}` parts filled in. */
    fun text(key: String): String

    /** The param evaluated as an expression. */
    fun eval(key: String): Value

    /** The param evaluated as a number; fails the block with a clear message if it isn't one. */
    fun number(key: String): Double

    fun bool(key: String): Boolean

    /** A variable name param, checked to be a usable name. Blank means "don't save". */
    fun variableName(key: String): String?

    fun getVar(name: String): Value
    fun setVar(name: String, value: Value)
    fun removeVar(name: String)

    fun log(message: String, level: LogLevel = LogLevel.INFO)

    /** Suspends until a wall-clock time. Survives the app sleeping between schedules. */
    suspend fun waitUntil(epochMs: Long)

    suspend fun sleep(ms: Long) = waitUntil(host.clock.now() + ms)
}

/** Thrown by executors for problems the user should read in the log, like a missing permission. */
class BlockException(message: String) : Exception(message)

enum class RunPhase { RUNNING, WAITING, PAUSED, SLEEPING, FINISHED, STOPPED, FAILED }

data class RunStatus(
    val flowId: String,
    val runId: String,
    val phase: RunPhase,
    val startedAt: Long,
    /** Blocks currently executing or waiting, for highlighting in the editor. */
    val activeBlocks: Set<String> = emptySet(),
    val detail: String = "",
    val wakeAt: Long? = null,
    val steps: Long = 0,
) {
    val isActive get() = phase == RunPhase.RUNNING || phase == RunPhase.WAITING || phase == RunPhase.PAUSED
}

/** A sleeping run, saved so the app can stop its service and pick up where it left off later. */
@Serializable
data class RunSnapshot(
    val flowId: String,
    val runId: String,
    val startedAt: Long,
    val steps: Long,
    val vars: Map<String, JsonPrimitive>,
    val fibers: List<FiberSnapshot>,
) {
    val wakeAt: Long get() = fibers.minOf { it.wakeAt }
}

@Serializable
data class FiberSnapshot(val blockId: String, val wakeAt: Long)
