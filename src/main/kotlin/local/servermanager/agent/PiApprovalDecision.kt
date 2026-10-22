package local.servermanager

/** The reviewer is advisory about scope. It cannot turn unverified effects into verified project-local effects. */
internal object PiApprovalDecision {
    fun effective(checked:PiToolPolicy.Checked,risk:ProjectAgentRisk):ProjectAgentRisk {
        val scope=checked.display.scope
        if(checked.bashAudit?.requiresIntent==true && !preAuthorized(checked.display,risk) && risk.action!="DENY")return risk.copy(action="ASK",risk=if(risk.risk=="LOW")"MEDIUM"else risk.risk,reason="fetch/pull의 정확한 사전 동의를 확인하지 못했습니다. ${risk.reason}")
        if(scope=="INSIDE")return if(preAuthorized(checked.display,risk))risk.copy(action="ALLOW",reason="사용자의 사전 명시 동의 반영 · ${risk.reason}")else risk
        val scopedFile=checked.display.tool.name in setOf("read","edit","write") || checked.bashAudit!=null
        // Requested ordinary outside files may be accessed by the owner's explicit approval.
        // Keep hard backend file/role/secret denials in PiToolPolicy; they never reach this function.
        if(risk.action!="DENY" || scopedFile)return risk.copy(action="ASK",risk=if(risk.risk=="LOW")"MEDIUM"else risk.risk,projectScope=scope,
            reason="백엔드 $scope · 명시적 승인 필요. 검토 원문 판정 ${risk.action}/${risk.risk}/${risk.projectScope}: ${risk.reason}".take(1000))
        return risk
    }
    private fun preAuthorized(plan:ProjectAgentPlan,risk:ProjectAgentRisk)=risk.userAuthorized && risk.action!="DENY" && risk.risk in setOf("LOW","MEDIUM") && plan.scope=="INSIDE" && risk.projectScope=="INSIDE" && plan.tool.name in setOf("bash","read","edit","write")
    fun human(explicit:Boolean,plan:ProjectAgentPlan,risk:ProjectAgentRisk,trustedGradle:Boolean):Boolean {
        if(!explicit && preAuthorized(plan,risk))return false
        return explicit && (!plan.readOnly || plan.tool.name=="bash") || !trustedGradle && (risk.action=="ASK" || risk.risk!="LOW" || plan.scope!="INSIDE" || risk.projectScope!="INSIDE")
    }
}
