package local.servermanager

/** Model review + human consent only. Backend path/shell classifications are informational. */
@Suppress("UNUSED_PARAMETER") // Compatibility for existing test/review callers; neither argument grants authority.
internal object PiApprovalDecision {
    fun effective(checked:PiToolPolicy.Checked,risk:ProjectAgentRisk):ProjectAgentRisk {
        // No backend UNKNOWN->ASK, LOW->MEDIUM or DENY->ASK rewrite.
        return if(preAuthorized(risk))risk.copy(action="ALLOW",reason="사용자의 사전 명시 동의 반영 · ${risk.reason}")else risk
    }
    private fun preAuthorized(risk:ProjectAgentRisk)=risk.userAuthorized && risk.action!="DENY" && risk.risk in setOf("LOW","MEDIUM")
    fun human(explicit:Boolean,plan:ProjectAgentPlan,risk:ProjectAgentRisk,trustedGradle:Boolean):Boolean {
        if(explicit && (!plan.readOnly || plan.tool.name=="bash"))return true
        if(preAuthorized(risk))return false
        return risk.action!="ALLOW" || risk.risk!="LOW"
    }
}
