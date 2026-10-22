package local.servermanager

class ModDependencyBlocked(message:String):IllegalArgumentException(message)

/** Exact Modrinth versions are authoritative. An unknown file is never proof of no dependencies. */
class ModGraph(snapshot:ModInventorySnapshot) {
    val active=snapshot.mods.filter {it.enabled}.associateBy {it.filename}
    val unknown=active.values.filter {it.source==null}.map {it.filename}.toSet()
    private val needs=active.mapValues {(_,m)->m.source?.dependencies.orEmpty().filter {it.type=="required"}.associateWith {d->
        active.values.filter {matches(it,d)}.map {it.filename}.toSet()
    }}
    val requirements=needs.mapValues {(file,deps)->deps.values.flatten().toSet()-file}
    val missing=needs.mapValues {(_,deps)->deps.filterValues {it.isEmpty()}.keys.map {label(it)}.toSet()}.filterValues {it.isNotEmpty()}
    val dependencyReasons:Map<String,List<String>> get()=requirements.entries.flatMap {(owner,deps)->deps.map {it to owner}}.groupBy({it.first},{it.second}).mapValues {it.value.sorted()}
    val dependencyFiles:Set<String> get()=dependencyReasons.keys
    val blockedReasons:Map<String,String> get()=active.keys.mapNotNull {f->
        val reason=when {
            f in dependencyFiles->"필수 의존성입니다. 사용 중: "+dependencyReasons.getValue(f).joinToString()
            else->null
        }
        reason?.let {f to it}
    }.toMap()
    fun validateEnabled(files:Set<String> = active.keys) {
        val unresolved=missing.filterKeys {it in files}
        require(unresolved.isEmpty()) {unresolved.entries.joinToString(" · "){"${it.key}: 필수 모드/버전 ${it.value.joinToString()}가 없습니다"}}
        validateCombination(active.values.filter {it.filename in files})
    }
    fun closure(files:Set<String>):Set<String> {
        require(files.all {it in active}) {"활성 모드를 선택하세요"}
        val result=files.toMutableSet();val pending=ArrayDeque(files)
        while(pending.isNotEmpty())requirements.getValue(pending.removeFirst()).forEach {if(result.add(it))pending.add(it)}
        return result
    }
    fun disable(files:Set<String>,ignoreDependencies:Boolean=false):Set<String> {
        require(files.isNotEmpty() && files.all {it in active}) {"비활성화할 활성 모드를 선택하세요"}
        val blocked=files.intersect(dependencyFiles)
        if(!ignoreDependencies && blocked.isNotEmpty())throw ModDependencyBlocked("필수 의존성 모드는 비활성화할 수 없습니다: "+blocked.sorted().joinToString(" · "){"$it (사용 중: ${dependencyReasons.getValue(it).joinToString()})"})
        // Mods Modrinth could not identify only produce a warning; their own dependencies are simply unknown.
        // Unrelated pre-existing missing dependencies must not prevent removing a leaf mod.
        return files
    }
    companion object {
        fun label(d:ModDependency)=(d.projectId ?: d.filename ?: "미확인 의존성")+(d.versionId?.let {"@$it"} ?: "")
        fun matches(mod:InstalledMod,d:ModDependency):Boolean {
            val s=mod.source ?: return false
            if(d.projectId==null)return false // filename-only claims cannot prove identity
            if(s.projectId==d.projectId && (d.versionId==null || d.versionId==s.versionId))return true
            return s.dependencies.any {it.type=="embedded" && it.projectId==d.projectId && (d.versionId==null || it.versionId==d.versionId)}
        }
        fun validateCombination(mods:Collection<InstalledMod>,changed:Set<String> = mods.map {it.filename}.toSet()) {
            val duplicate=mods.mapNotNull {m->m.source?.let {it.projectId to m.filename}}.groupBy({it.first},{it.second}).filterValues {it.size>1 && it.any {file->file in changed}}
            require(duplicate.isEmpty()) {"같은 모드가 여러 파일로 켜져 있습니다: "+duplicate.keys.joinToString()}
            val clash=mods.flatMap {m->m.source?.dependencies.orEmpty().filter {it.type=="incompatible" && mods.any {p->p.filename!=m.filename && (m.filename in changed || p.filename in changed) && matches(p,it)}}.map {"${m.filename} / ${label(it)}"}}
            require(clash.isEmpty()) {"Modrinth 비호환 조합입니다: "+clash.joinToString()}
        }
        /** Restore exact required providers, never a different version of the same project. */
        fun enable(snapshot:ModInventorySnapshot,files:Set<String>,ignoreDependencies:Boolean=false):Pair<Set<String>,List<String>> {
            val disabled=snapshot.mods.filter {!it.enabled}.associateBy {it.filename}
            require(files.isNotEmpty() && files.all {it in disabled}) {"다시 켤 비활성 모드를 선택하세요"}
            if(ignoreDependencies)return files to listOf("의존성·비호환 검사를 무시하고 선택한 모드만 강제로 켭니다. 서버가 시작되지 않거나 충돌할 수 있습니다.")
            val enabled=snapshot.mods.filter {it.enabled}
            try {
                val result=files.toMutableSet();var changed=true
                while(changed) {
                    changed=false
                    for(file in result.toList()) {
                        val source=disabled.getValue(file).source
                        for(dep in source?.dependencies.orEmpty().filter {it.type=="required"}) {
                            val proposed=enabled+result.map(disabled::getValue)
                            if(proposed.any {matches(it,dep)})continue
                            val candidates=disabled.values.filter {it.filename !in result && matches(it,dep)}
                            require(candidates.size==1) {"$file: 필수 모드/버전 ${label(dep)}을 하나로 확인할 수 없습니다. Modrinth에서 먼저 설치하세요."}
                            result.add(candidates.single().filename);changed=true
                        }
                    }
                }
                // Existing unrelated conflicts must not prevent restoring an independent mod.
                val added=result.map(disabled::getValue)
                validateCombination(enabled+added,result)
                val unverified=result.filter {disabled.getValue(it).source==null}.sorted()
                return result to if(unverified.isEmpty())emptyList() else listOf("Modrinth에서 찾지 못한 모드는 필수 의존성을 확인하지 못했습니다: "+unverified.joinToString())
            } catch(e:IllegalArgumentException) {throw ModDependencyBlocked(e.message ?: "의존성 검사를 통과하지 못했습니다")}
        }
    }
    fun plan(suspects:Set<String>,keep:Set<String>):ModSearchPlan {
        require(suspects.isNotEmpty() && suspects.all {it in active}) {"검사할 의심 모드를 선택하세요"}
        validateEnabled()
        val fixed=closure(keep+dependencyFiles)
        val candidates=(suspects-fixed).sorted()
        require(candidates.isNotEmpty()) {"선택한 모드는 모두 유지 모드 또는 필수 의존성입니다"}
        return ModSearchPlan(active.keys.sorted(),suspects.sorted(),fixed.sorted(),(suspects intersect fixed).sorted(),candidates.mapIndexed {n,f->ModSearchGroup("g${n+1}",listOf(f))})
    }
    fun disabled(plan:ModSearchPlan,enabledGroups:Set<String>):Set<String> {
        require(enabledGroups.all {g->plan.groups.any {it.id==g}})
        val removed=plan.groups.filter {it.id !in enabledGroups}.flatMap {it.files}.toSet()
        val disabled=if(removed.isEmpty())emptySet() else disable(removed)
        check(disabled.intersect(plan.fixed.toSet()).isEmpty()) {"유지 모드의 필수 의존성이 제거됩니다"}
        validateEnabled(active.keys-disabled)
        return disabled
    }
}
data class ModSearchGroup(val id:String,val files:List<String>)
data class ModSearchPlan(val original:List<String>,val suspects:List<String>,val fixed:List<String>,val protectedSuspects:List<String>,val groups:List<ModSearchGroup>)
data class SearchObservation(val groups:List<String>,val verdict:String)
data class SearchStep(val status:String,val groups:List<String>,val message:String)

/** Replayable delta debugging. Only explicit PASS/FAIL observations influence the next trial. */
object ManualModSearch {
    fun next(all:List<String>,history:List<SearchObservation>):SearchStep {
        require(all.distinct().size==all.size && all.size<=2000)
        val known=history.associate {it.groups.toSortedSet().toList() to it.verdict}
        fun verdict(set:List<String>)=known[set.sorted()]
        fun pending(set:List<String>,message:String)=SearchStep(if(verdict(set)=="INVALID")"INCONCLUSIVE" else "TEST",set.sorted(),message)
        when(verdict(all)) {
            "PASS"->return SearchStep("NOT_REPRODUCED",all,"선택한 모드를 모두 켜도 문제가 재현되지 않았습니다. 재현 조건을 확인하고 다시 시도하세요.")
            "FAIL"->{}
            else->return pending(all,"먼저 모든 의심 모드를 켜고 원래 문제가 재현되는지 확인하세요.")
        }
        when(verdict(emptyList())) {
            "FAIL"->return SearchStep("OUTSIDE_SELECTION",emptyList(),"의심 모드를 모두 꺼도 문제가 발생합니다. 선택 범위 밖의 원인 또는 다른 재현 조건을 확인하세요.")
            "PASS"->{}
            else->return pending(emptyList(),"의심 모드를 모두 끈 비교 시도입니다. 같은 문제의 발생 여부를 판정하세요.")
        }
        var candidates=all.sorted();var granularity=2
        while(candidates.size>=2) {
            val chunks=candidates.chunked((candidates.size+granularity-1)/granularity)
            val trials=(chunks+chunks.map {candidates-it.toSet()}).distinct().filter {it.isNotEmpty() && it.size<candidates.size}
            var smaller:List<String>?=null
            for(trial in trials)when(verdict(trial)) {
                "FAIL"->{smaller=trial;break}
                "PASS"->{}
                else->return pending(trial,"선택한 의심 모드를 나누어 확인합니다. 동일한 문제만 실패로 판정하세요.")
            }
            if(smaller!=null){candidates=smaller;granularity=maxOf(2,granularity-1);continue}
            if(granularity>=candidates.size)break
            granularity=minOf(candidates.size,granularity*2)
        }
        return SearchStep("FOUND",candidates,if(candidates.size==1)"문제를 재현하는 모드 묶음을 찾았습니다. 묶음 안의 필수 의존성은 함께 유지됩니다." else "각 묶음을 하나씩 빼면 재현되지 않는 최소 조합입니다. 모드 간 상호작용일 수 있습니다.")
    }
}
