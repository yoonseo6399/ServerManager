package local.servermanager

/** The reviewer is advisory about scope. It cannot turn unverified effects into verified project-local effects. */
internal object PiApprovalDecision {
    fun effective(checked:PiToolPolicy.Checked,risk:ProjectAgentRisk):ProjectAgentRisk {
        val scope=checked.display.scope
        if(scope=="INSIDE")return risk
        val scopedFile=checked.display.tool.name in setOf("read","edit","write") || checked.bashAudit!=null
        // Requested ordinary outside files may be accessed by the owner's explicit approval.
        // Keep hard backend file/role/secret denials in PiToolPolicy; they never reach this function.
        if(risk.action!="DENY" || scopedFile)return risk.copy(action="ASK",risk=if(risk.risk=="LOW")"MEDIUM"else risk.risk,projectScope=scope,
            reason="백엔드 $scope · 명시적 승인 필요. 검토 원문 판정 ${risk.action}/${risk.risk}/${risk.projectScope}: ${risk.reason}".take(1000))
        return risk
    }
    fun human(explicit:Boolean,plan:ProjectAgentPlan,risk:ProjectAgentRisk,trustedGradle:Boolean)=
        explicit && (!plan.readOnly || plan.tool.name=="bash") || !trustedGradle && (risk.action=="ASK" || risk.risk!="LOW" || plan.scope!="INSIDE" || risk.projectScope!="INSIDE")
}
