package dev.autoflow.core.engine

import dev.autoflow.core.blocks.BlockCatalog
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.blocks.ParamKind
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.expr.Expr
import dev.autoflow.core.expr.ExprException
import dev.autoflow.core.model.FlowDoc

data class Issue(val blockId: String?, val severity: Severity, val message: String) {
    enum class Severity { ERROR, WARNING }
}

/** Checks a flow before it runs, so mistakes show up in the editor instead of halfway through a run. */
object Validator {
    fun check(doc: FlowDoc, specs: (String) -> BlockSpec? = { BlockCatalog[it] }): List<Issue> {
        val issues = mutableListOf<Issue>()
        val starts = doc.blocks.filter { it.type == FlowEngine.START_TYPE }
        if (starts.isEmpty()) issues += Issue(null, Issue.Severity.ERROR, "Add a Flow start block so the flow knows where to begin")

        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque(starts.map { it.id })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!reachable.add(id)) continue
            doc.links.filter { it.from == id }.forEach { queue += it.to }
        }

        for (block in doc.blocks) {
            val spec = specs(block.type)
            if (spec == null) {
                issues += Issue(block.id, Issue.Severity.ERROR, "Unknown block type ${block.type}")
                continue
            }
            if (starts.isNotEmpty() && block.id !in reachable) {
                issues += Issue(block.id, Issue.Severity.WARNING, "${spec.title} is never reached: no arrow leads to it")
            }
            val values = spec.resolve(block.params)
            for (p in spec.params) {
                if (!p.isVisible(values, spec.params)) continue
                val v = values[p.key].orEmpty()
                val problem = when (p.kind) {
                    ParamKind.EXPRESSION, ParamKind.NUMBER -> if (v.isBlank() && p.kind == ParamKind.NUMBER) "is empty"
                    else try { if (v.isNotBlank()) Expr.parse(v); null } catch (e: ExprException) { e.message }
                    ParamKind.VARIABLE -> if (v.isNotBlank() && !FlowEngine.VAR_NAME.matches(v.trim())) "can only use letters, digits and _" else null
                    ParamKind.TIME -> if (LogicExecutors.parseTime(v) == null) "should look like 07:30" else null
                    else -> null
                }
                if (problem != null) {
                    issues += Issue(block.id, Issue.Severity.ERROR, "${spec.title} → ${p.label} $problem")
                }
            }
        }
        return issues
    }

    /** Every permission the flow's blocks need, in a stable order. */
    fun permissions(doc: FlowDoc, specs: (String) -> BlockSpec? = { BlockCatalog[it] }): List<Permission> =
        doc.blocks.flatMap { b -> specs(b.type)?.requiredPermissions(b.params).orEmpty() }
            .distinct()
            .sortedBy { it.ordinal }
}
