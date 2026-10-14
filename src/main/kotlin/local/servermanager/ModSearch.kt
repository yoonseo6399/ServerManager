package local.servermanager

/**
 * Required edges come from Modrinth version dependencies, matched by project ID between top-level files.
 * Files Modrinth does not know have no known requirements; they never block changes to other mods.
 */
class ModGraph(snapshot:ModInventorySnapshot) {
    val active=snapshot.mods.filter {it.enabled}.associateBy {it.filename}
    /** Per file, each required project and the active files providing it. */
    private val needs:Map<String,Map<String,Set<String>>>
    val requirements:Map<String,Set<String>>
    /** Required Modrinth projects that no active file provides, keyed by the requiring file. */
    val missing:Map<String,Set<String>>
    init {
        val providers=providers(active.values)
        // A client-only file cannot load on the server, so its requirements protect nothing there.
        needs=active.mapValues {(file,mod)->mod.source?.takeUnless {it.clientOnly}?.required.orEmpty().associateWith {providers[it].orEmpty()-file}}
        requirements=needs.mapValues {it.value.values.flatten().toSet()}
        missing=needs.mapValues {(file,deps)->deps.filter {(project,files)->files.isEmpty() && project !in active.getValue(file).source!!.provides}.keys}.filterValues {it.isNotEmpty()}
    }
    val dependencyReasons:Map<String,List<String>> get()=requirements.entries.flatMap {(owner,deps)->deps.map {it to owner}}.groupBy({it.first},{it.second}).mapValues {it.value.sorted()}
    val dependencyFiles:Set<String> get()=dependencyReasons.keys
    fun closure(files:Set<String>):Set<String> {
        require(files.all {it in active}) {"활성 모드를 선택하세요"}
        val result=files.toMutableSet();val pending=ArrayDeque(files)
        while(pending.isNotEmpty())requirements.getValue(pending.removeFirst()).forEach {if(result.add(it))pending.add(it)}
        return result
    }
    /** Disabling a provider also disables every mod left without any provider of a required project. */
    fun disable(files:Set<String>):Set<String> {
        require(files.isNotEmpty() && files.all {it in active}) {"비활성화할 활성 모드를 선택하세요"}
        val result=files.toMutableSet();var changed=true
        while(changed) {
            changed=false
            for((file,deps) in needs)if(file !in result && deps.values.any {it.isNotEmpty() && it.all {p->p in result}}){result.add(file);changed=true}
        }
        return result
    }
    companion object {
        fun providers(mods:Collection<InstalledMod>)=mods.flatMap {m->m.source?.provides.orEmpty().map {it to m.filename}}.groupBy({it.first},{it.second}).mapValues {it.value.toSet()}
        /**
         * Re-enabling also restores disabled files that provide a missing required project.
         * Requirements nothing can satisfy are reported, not blocking: a non-Modrinth file may provide them.
         */
        fun enable(snapshot:ModInventorySnapshot,files:Set<String>):Pair<Set<String>,List<String>> {
            val disabled=snapshot.mods.filter {!it.enabled}.associateBy {it.filename}
            require(files.isNotEmpty() && files.all {it in disabled}) {"다시 켤 비활성 모드를 선택하세요"}
            val enabled=snapshot.mods.filter {it.enabled}
            fun title(project:String)=snapshot.projects[project]?.title ?: project
            val result=files.toMutableSet();val warnings=mutableListOf<String>()
            var changed=true
            while(changed) {
                changed=false
                val provided=providers(enabled+result.map(disabled::getValue)).keys
                for(file in result.toList())for(project in disabled.getValue(file).source?.takeUnless {it.clientOnly}?.required.orEmpty()-provided) {
                    val candidates=disabled.values.filter {it.filename !in result && project in it.source?.provides.orEmpty()}
                    if(candidates.size==1){result.add(candidates.single().filename);changed=true}
                }
            }
            val proposed=enabled+result.map(disabled::getValue)
            val provided=providers(proposed).keys
            for(file in result.sorted())for(project in disabled.getValue(file).source?.takeUnless {it.clientOnly}?.required.orEmpty()-provided)
                warnings.add("$file: 필수 모드 ${title(project)}을(를) 찾지 못했습니다")
            val duplicate=proposed.mapNotNull {m->m.source?.let {it.projectId to m}}.groupBy({it.first},{it.second}).filter {(_,mods)->mods.size>1 && mods.any {it.filename in result}}
            require(duplicate.isEmpty()) {"같은 모드의 다른 파일이 이미 켜져 있습니다: "+duplicate.keys.joinToString {title(it)}}
            val clash=proposed.flatMap {m->m.source?.dependencies.orEmpty().filter {it.type=="incompatible" && it.projectId in provided}.map {m to it.projectId}}
                .filter {(m,project)->m.filename in result || proposed.any {it.filename in result && project in it.source?.provides.orEmpty()}}
            require(clash.isEmpty()) {"Modrinth에 서로 호환되지 않는다고 등록된 모드입니다: "+clash.joinToString {(m,project)->"${m.source!!.title} / ${title(project)}"}}
            return result to warnings
        }
    }
    fun plan(suspects:Set<String>,keep:Set<String>):ModSearchPlan {
        require(suspects.isNotEmpty() && suspects.all {it in active}) {"검사할 의심 모드를 선택하세요"}
        val fixed=closure(keep+dependencyFiles)
        val candidates=(suspects-fixed).sorted()
        require(candidates.isNotEmpty()) {"선택한 모드는 모두 유지 모드 또는 필수 의존성입니다"}
        val groups=mutableListOf<MutableSet<String>>()
        for(f in candidates) {
            val related=closure(setOf(f)).intersect(candidates.toSet())+f
            val overlaps=groups.filter {it.any {n->n in related}}
            val merged=related.toMutableSet();overlaps.forEach {merged.addAll(it)};groups.removeAll(overlaps.toSet());groups.add(merged)
        }
        // A late merge can join earlier groups through a transitive provider.
        var changed=true
        while(changed){changed=false;outer@for(a in groups.indices)for(b in a+1 until groups.size)if(groups[a].intersect(groups[b]).isNotEmpty()){groups[a].addAll(groups.removeAt(b));changed=true;break@outer}}
        return ModSearchPlan(active.keys.sorted(),suspects.sorted(),fixed.sorted(),(suspects intersect fixed).sorted(),groups.sortedBy {it.min()}.mapIndexed {n,g->ModSearchGroup("g${n+1}",g.sorted())})
    }
    fun disabled(plan:ModSearchPlan,enabledGroups:Set<String>):Set<String> {
        require(enabledGroups.all {g->plan.groups.any {it.id==g}})
        val removed=plan.groups.filter {it.id !in enabledGroups}.flatMap {it.files}.toSet()
        val disabled=if(removed.isEmpty())emptySet() else disable(removed)
        check(disabled.intersect(plan.fixed.toSet()).isEmpty()) {"유지 모드의 필수 의존성이 제거됩니다"}
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
