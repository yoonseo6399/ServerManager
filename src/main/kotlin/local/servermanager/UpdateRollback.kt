package local.servermanager

import java.nio.file.Path
import kotlin.io.path.*

/** Explicit human consent only; neither model output nor the automatic updater can create an approval. */
object UpdateRollback {
    fun stableTarget(version:String)=runCatching {SignedUpdates.compare(version,"0.6.0")>=0 && "-beta." !in version}.getOrDefault(false)
    fun approved(home:Path,target:String,current:String,supported:Boolean):Boolean = supported && runCatching {
        SignedUpdates.compare(target,current)<0 && stableTarget(target) && home.resolve("updates/rollback-approved.txt").readText().trim()==target
    }.getOrDefault(false)
    fun pending(home:Path,current:String,supported:Boolean):String? = runCatching {
        home.resolve("updates/pending.txt").readText().trim().takeIf {SignedUpdates.compare(it,current)>0 || approved(home,it,current,supported)}
    }.getOrNull()
    fun stageConsent(home:Path,target:String,current:String,allowRollback:Boolean,supported:Boolean) {
        val marker=home.resolve("updates/rollback-approved.txt")
        if(SignedUpdates.compare(target,current)<0){
            require(allowRollback && supported && stableTarget(target)) {"새 설치판과 안정판 롤백 동의가 필요합니다"}
            atomicText(marker,target)
        }else marker.deleteIfExists()
    }
}
