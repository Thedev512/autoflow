package dev.autoflow.core.engine

import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.expr.EvalEnv
import dev.autoflow.core.expr.Expr
import dev.autoflow.core.expr.ExprException
import dev.autoflow.core.expr.Template
import dev.autoflow.core.expr.Value
import dev.autoflow.core.model.Block
import dev.autoflow.core.model.FlowDoc
import dev.autoflow.core.model.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs flows. Each run walks the graph from its "Flow start" blocks (one coroutine, or "fiber",
 * per start block), executing one block at a time and following the output port it returns.
 * Many flows can run at once; each can be paused, resumed or stopped on its own.
 */
class FlowEngine(
    private val executors: Map<String, Executor>,
    private val host: Host,
    private val logSink: LogSink,
    private val scope: CoroutineScope,
    private val specs: (String) -> BlockSpec? = { BlockCatalog[it] },
) {
    private val _statuses = MutableStateFlow<Map<String, RunStatus>>(emptyMap())

    /** Latest status per flow id, including finished and failed runs until the flow starts again. */
    val statuses: StateFlow<Map<String, RunStatus>> = _statuses.asStateFlow()

    private val runs = ConcurrentHashMap<String, Run>()
    private val lock = Any()

    fun isActive(flowId: String) = runs.containsKey(flowId)
    fun activeCount() = runs.size

    /** Starts a flow, or resumes a sleeping one from [snapshot]. Returns false if it is already running. */
    fun start(flow: FlowDoc, snapshot: RunSnapshot? = null): Boolean {
        val starts: List<Pair<Block, Long?>> = if (snapshot != null) {
            snapshot.fibers.mapNotNull { f -> flow.block(f.blockId)?.let { it to f.wakeAt } }
        } else {
            flow.blocks.filter { it.type == START_TYPE }.map { it to null }
        }
        val run = synchronized(lock) {
            if (runs.containsKey(flow.id)) return false
            Run(flow, snapshot?.runId ?: Ids.next(8), snapshot?.startedAt ?: host.clock.now()).also { runs[flow.id] = it }
        }
        snapshot?.let { s ->
            s.vars.forEach { (k, v) -> run.vars[k] = Value.fromJson(v) }
            run.steps.set(s.steps)
        }
        if (starts.isEmpty()) {
            runs.remove(flow.id)
            val msg = if (snapshot != null) "Couldn't resume: the flow was changed while it slept"
            else "Add a Flow start block to tell the flow where to begin"
            log(run, null, LogLevel.ERROR, msg)
            publish(run, RunPhase.FAILED, msg)
            return false
        }
        log(run, null, LogLevel.INFO, if (snapshot != null) "Woke up" else "Flow started")
        publish(run)
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            val fiberJobs = starts.map { (block, wake) ->
                val fiber = Fiber(block.id, wake)
                run.fibers += fiber
                launch { runFiber(run, fiber) }
            }
            try {
                fiberJobs.joinAll()
            } finally {
                finish(run)
            }
        }
        run.job!!.start()
        return true
    }

    fun stop(flowId: String) {
        val run = runs[flowId] ?: return
        run.endPhase = RunPhase.STOPPED
        run.endDetail = "Stopped"
        run.job?.cancel()
    }

    fun stopAll() = runs.keys.toList().forEach { stop(it) }

    fun pause(flowId: String) {
        val run = runs[flowId] ?: return
        run.paused.value = true
        log(run, null, LogLevel.INFO, "Paused")
        publish(run)
    }

    fun resume(flowId: String) {
        val run = runs[flowId] ?: return
        run.paused.value = false
        log(run, null, LogLevel.INFO, "Resumed")
        publish(run)
    }

    /** Forget a finished run's status (e.g. when the flow is deleted). */
    fun clearStatus(flowId: String) {
        if (!runs.containsKey(flowId)) _statuses.update { it - flowId }
    }

    /** Sets the shown status of a flow that isn't running here (used for flows asleep on disk). */
    fun setExternalStatus(status: RunStatus) {
        if (!runs.containsKey(status.flowId)) _statuses.update { it + (status.flowId to status) }
    }

    /**
     * If every running flow is idle, waiting on a timer that ends at least [minSleepMs] from now,
     * saves them as snapshots and stops them so the app can sleep. Returns null when not possible.
     */
    fun hibernate(minSleepMs: Long): List<RunSnapshot>? {
        val now = host.clock.now()
        val snapshots = synchronized(lock) {
            if (runs.isEmpty()) return null
            val all = runs.values.toList()
            val ok = all.all { run ->
                !run.paused.value && run.fibers.isNotEmpty() && run.fibers.all { f ->
                    val w = f.waitingUntil
                    !f.done && w != null && w - now >= minSleepMs
                }
            }
            if (!ok) return null
            all.map { run ->
                run.endPhase = RunPhase.SLEEPING
                RunSnapshot(
                    flowId = run.flow.id,
                    runId = run.runId,
                    startedAt = run.startedAt,
                    steps = run.steps.get(),
                    vars = run.vars.mapValues { it.value.toJson() },
                    fibers = run.fibers.filter { !it.done }.map { FiberSnapshot(it.blockId!!, it.waitingUntil!!) },
                ).also { snap ->
                    run.endDetail = "Sleeping"
                    run.sleepUntil = snap.wakeAt
                }
            }
        }
        snapshots.forEach { s -> runs[s.flowId]?.job?.cancel() }
        return snapshots
    }

    // ------------------------------------------------------------------

    private inner class Run(val flow: FlowDoc, val runId: String, val startedAt: Long) {
        val vars = ConcurrentHashMap<String, Value>()
        val fibers = java.util.concurrent.CopyOnWriteArrayList<Fiber>()
        val paused = MutableStateFlow(false)
        val steps = AtomicLong(0)
        val blocksById = flow.blocks.associateBy { it.id }
        val linkMap = flow.links.associateBy { it.from to it.port }
        var job: Job? = null
        @Volatile var endPhase: RunPhase? = null
        @Volatile var endDetail: String = ""
        @Volatile var sleepUntil: Long? = null
        @Volatile var stepLogBudget = STEP_LOG_BUDGET
        val env = EvalEnv(
            lookup = { name -> vars[name] },
            now = { host.clock.now() },
            zone = host.clock.zone,
        )
    }

    private class Fiber(startBlock: String, var pendingWake: Long?) {
        @Volatile var blockId: String? = startBlock
        @Volatile var waiting = false
        @Volatile var waitingUntil: Long? = null
        @Volatile var done = false
    }

    private suspend fun runFiber(run: Run, fiber: Fiber) {
        var burst = 0
        var current: Block? = run.blocksById[fiber.blockId]
        try {
            while (current != null) {
                if (run.paused.value) {
                    publish(run)
                    run.paused.first { !it }
                }
                val block = current
                val spec = specs(block.type)
                val executor = executors[block.type]
                if (spec == null || executor == null) {
                    fail(run, block, spec, "This block (${block.type}) isn't supported in this version")
                    return
                }
                fiber.blockId = block.id
                fiber.waiting = spec.waits
                publish(run)
                val ctx = Ctx(run, fiber, block, spec)
                val t0 = host.clock.now()
                val port: String? = try {
                    executor(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = friendly(e)
                    if (spec.outputs.any { it.id == FAIL_PORT }) {
                        run.vars["error"] = Value.Str(msg)
                        log(run, block, LogLevel.WARN, msg)
                        FAIL_PORT
                    } else {
                        fail(run, block, spec, msg)
                        return
                    }
                } finally {
                    fiber.waiting = false
                    fiber.waitingUntil = null
                }
                val took = host.clock.now() - t0
                run.steps.incrementAndGet()
                if (spec.waits) {
                    run.stepLogBudget = STEP_LOG_BUDGET
                    burst = 0
                }
                if (run.stepLogBudget > 0) {
                    run.stepLogBudget--
                    val portLabel = spec.outputs.firstOrNull { it.id == port }?.label?.takeIf { it.isNotEmpty() }
                    val msg = when {
                        port == null -> "Done"
                        portLabel != null -> "→ $portLabel"
                        else -> "OK"
                    }
                    log(run, block, LogLevel.DEBUG, msg, took)
                    if (run.stepLogBudget == 0) {
                        log(run, block, LogLevel.WARN, "Busy loop: step logging paused until the flow waits for something")
                    }
                }
                current = port?.let { run.linkMap[block.id to it] }?.let { run.blocksById[it.to] }
                // Keep tight loops from draining the battery or starving other flows.
                if (++burst >= BURST_LIMIT) {
                    burst = 0
                    delay(BURST_PAUSE_MS)
                } else {
                    yield()
                }
            }
        } finally {
            fiber.done = true
            fiber.blockId = null
        }
    }

    private fun fail(run: Run, block: Block, spec: BlockSpec?, message: String) {
        log(run, block, LogLevel.ERROR, message)
        run.endPhase = RunPhase.FAILED
        run.endDetail = "${spec?.title ?: block.type}: $message"
        run.job?.cancel()
    }

    private fun finish(run: Run) {
        synchronized(lock) { runs.remove(run.flow.id, run) }
        val phase = run.endPhase ?: RunPhase.FINISHED
        when (phase) {
            RunPhase.FINISHED -> log(run, null, LogLevel.INFO, "Flow finished")
            RunPhase.STOPPED -> log(run, null, LogLevel.INFO, "Flow stopped")
            RunPhase.SLEEPING -> log(run, null, LogLevel.INFO, "Sleeping until the next scheduled time")
            else -> Unit
        }
        val status = RunStatus(
            flowId = run.flow.id,
            runId = run.runId,
            phase = phase,
            startedAt = run.startedAt,
            detail = run.endDetail.ifEmpty { if (phase == RunPhase.FINISHED) "Finished" else "" },
            wakeAt = run.sleepUntil,
            steps = run.steps.get(),
        )
        _statuses.update { it + (run.flow.id to status) }
    }

    private fun publish(run: Run, phaseOverride: RunPhase? = null, detail: String? = null) {
        if (phaseOverride == null && !runs.containsKey(run.flow.id)) return
        val live = run.fibers.filter { !it.done && it.blockId != null }
        val busy = live.filter { !it.waiting }
        val phase = phaseOverride ?: when {
            run.paused.value -> RunPhase.PAUSED
            busy.isNotEmpty() || live.isEmpty() -> RunPhase.RUNNING
            else -> RunPhase.WAITING
        }
        val waitingOn = live.firstOrNull { it.waiting }?.blockId?.let { run.blocksById[it] }
        val text = detail ?: when (phase) {
            RunPhase.PAUSED -> "Paused"
            RunPhase.WAITING -> "Waiting: " + (waitingOn?.let { titleOf(it) } ?: "")
            RunPhase.RUNNING -> busy.firstOrNull()?.blockId?.let { run.blocksById[it] }?.let { "Running: " + titleOf(it) } ?: "Running"
            else -> ""
        }
        val status = RunStatus(
            flowId = run.flow.id,
            runId = run.runId,
            phase = phase,
            startedAt = run.startedAt,
            activeBlocks = live.mapNotNull { it.blockId }.toSet(),
            detail = text,
            wakeAt = live.mapNotNull { it.waitingUntil }.minOrNull(),
            steps = run.steps.get(),
        )
        _statuses.update { it + (run.flow.id to status) }
    }

    private fun titleOf(block: Block): String =
        block.label.ifBlank { specs(block.type)?.title ?: block.type }

    private fun log(run: Run, block: Block?, level: LogLevel, message: String, duration: Long? = null) {
        runCatching {
            logSink.log(
                LogEntry(
                    flowId = run.flow.id,
                    runId = run.runId,
                    time = host.clock.now(),
                    level = level,
                    blockId = block?.id,
                    blockTitle = block?.let { titleOf(it) },
                    message = message,
                    durationMs = duration,
                )
            )
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is BlockException, is ExprException -> e.message ?: "Error"
        is SecurityException -> "Android blocked this: ${e.message ?: "missing permission"}"
        else -> "${e::class.simpleName}: ${e.message ?: "no details"}"
    }

    private inner class Ctx(
        val run: Run,
        val fiber: Fiber,
        override val block: Block,
        override val spec: BlockSpec,
    ) : RunContext {
        override val flow get() = run.flow
        override val runId get() = run.runId
        override val host get() = this@FlowEngine.host
        override val env get() = run.env
        private val params = spec.resolve(block.params)

        override fun raw(key: String) = params[key] ?: ""
        override fun text(key: String) = try {
            Template.render(raw(key), env)
        } catch (e: ExprException) {
            throw ExprException("${labelOf(key)}: ${e.message}")
        }

        override fun eval(key: String): Value = try {
            Expr.evaluate(raw(key), env)
        } catch (e: ExprException) {
            throw ExprException("${labelOf(key)}: ${e.message}")
        }

        override fun number(key: String): Double {
            val v = eval(key).asNumber()
            if (v.isNaN()) throw BlockException("${labelOf(key)} should be a number, got “${eval(key).asText()}”")
            return v
        }

        override fun bool(key: String): Boolean = when (raw(key).trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0", "" -> false
            else -> eval(key).isTruthy()
        }

        override fun variableName(key: String): String? {
            val name = raw(key).trim().removePrefix("{").removeSuffix("}")
            if (name.isEmpty()) return null
            if (!VAR_NAME.matches(name)) throw BlockException("“$name” can't be a variable name. Use letters, digits and _")
            return name
        }

        private fun labelOf(key: String) = spec.params.firstOrNull { it.key == key }?.label ?: key

        override fun getVar(name: String) = run.vars[name] ?: Value.Null
        override fun setVar(name: String, value: Value) { run.vars[name] = value }
        override fun removeVar(name: String) { run.vars.remove(name) }

        override fun log(message: String, level: LogLevel) = log(run, block, level, message)

        override suspend fun waitUntil(epochMs: Long) {
            // After waking from sleep, finish the wait we were in rather than starting a new one.
            val target = fiber.pendingWake ?: epochMs
            fiber.pendingWake = null
            fiber.waitingUntil = target
            publish(run)
            try {
                host.delayUntil(target)
            } finally {
                fiber.waitingUntil = null
            }
        }
    }

    companion object {
        const val START_TYPE = "flow.start"
        const val FAIL_PORT = "fail"
        private const val BURST_LIMIT = 500
        private const val BURST_PAUSE_MS = 20L
        private const val STEP_LOG_BUDGET = 200
        val VAR_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
