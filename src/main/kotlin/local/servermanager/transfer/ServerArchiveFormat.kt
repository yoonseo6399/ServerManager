package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.*

data class ServerArchivePlan(val name:String,val root:String,val files:Int,val bytes:Long,
    val loader:String?=null,val minecraft:String?=null,val loaderVersion:String?=null,
    val entryJar:String?=null,val requiredJava:Int?=null,val jars:List<String> = emptyList(),val warnings:List<String> = emptyList())

/** Server ZIPs are copied as data. Neither an installer nor a shell script from an upload is executed. */
object ServerArchiveFormat {
    const val MAX_ARCHIVE=20L*1024*1024*1024
    const val MAX_TOTAL=100L*1024*1024*1024
    const val MAX_ENTRY=20L*1024*1024*1024
    const val MAX_ENTRIES=100000
    const val CHUNK=8*1024*1024
    private val mapper=jacksonObjectMapper()
    private val windowsNames=Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
    internal fun relative(raw:String):String {
        val value=raw.removeSuffix("/")
        require(value.isNotBlank() && value.length<=2048 && !value.startsWith('/') && '\\' !in value && ':' !in value && value.none {it.isISOControl()}) {"잘못된 ZIP 경로입니다: ${raw.take(100)}"}
        require(value.split('/').all {it.isNotEmpty() && it !in setOf(".","..") && !it.endsWith('.') && !it.endsWith(' ') && !windowsNames.matches(it)}) {"서버 폴더를 벗어나거나 운영체제에서 사용할 수 없는 ZIP 경로입니다: ${raw.take(100)}"}
        return value
    }
    private fun ignored(name:String)=name.split('/').any {it=="__MACOSX" || it==".DS_Store"}
    private fun entries(zip:ZipFile):List<ZipEntry> {
        val list=mutableListOf<ZipEntry>();val seen=mutableMapOf<String,Boolean>();var total=0L
        val iterator=zip.entries()
        while(iterator.hasMoreElements()) {
            require(list.size<MAX_ENTRIES) {"ZIP 항목 수는 $MAX_ENTRIES 개 이하여야 합니다"}
            val entry=iterator.nextElement();val name=relative(entry.name)
            val key=Normalizer.normalize(name,Normalizer.Form.NFC).lowercase(Locale.ROOT)
            require(seen.putIfAbsent(key,entry.isDirectory)==null) {"대소문자·유니코드를 포함해 중복된 ZIP 경로입니다: ${name.take(100)}"}
            require(entry.method in setOf(ZipEntry.STORED,ZipEntry.DEFLATED) && entry.size in 0..MAX_ENTRY) {"지원하지 않는 압축 방식 또는 파일 크기입니다: ${name.take(100)}"}
            total+=entry.size;require(total<=MAX_TOTAL) {"압축 해제 크기는 100 GiB 이하여야 합니다"}
            list.add(entry)
        }
        seen.forEach {(name,_)->var parent=name.substringBeforeLast('/',"");while(parent.isNotEmpty()){require(seen[parent]!=false) {"파일과 폴더 경로가 겹칩니다: ${name.take(100)}"};parent=parent.substringBeforeLast('/',"")}}
        return list
    }
    fun inspect(archive:Path,filename:String):ServerArchivePlan=ZipFile(archive.toFile()).use {zip->
        val entries=entries(zip).filter {!it.isDirectory && !ignored(it.name)}
        require(entries.isNotEmpty()) {"ZIP 안에 서버 파일이 없습니다"}
        val roots=entries.filter {it.name.substringAfterLast('/')=="server.properties"}.map {it.name.substringBeforeLast('/',"")}.distinct()
        require(roots.size<=1) {"여러 서버 폴더가 들어 있습니다. 한 서버 폴더만 ZIP으로 압축하세요"}
        var prefix=roots.singleOrNull()?.let {if(it.isEmpty())"" else "$it/"} ?: ""
        if(roots.isEmpty()) {
            while(entries.all {it.name.removePrefix(prefix).contains('/')} && entries.map {it.name.removePrefix(prefix).substringBefore('/')}.distinct().size==1)
                prefix+=entries.first().name.removePrefix(prefix).substringBefore('/')+"/"
        }
        val files=entries.filter {it.name.startsWith(prefix)};val names=files.map {it.name.removePrefix(prefix)}.toSet()
        require(names.any {it=="server.properties" || it.endsWith(".jar") && '/' !in it || it.endsWith("/unix_args.txt") || it.endsWith("/win_args.txt") || it.endsWith("/level.dat")}) {"서버 설정·실행 파일·월드가 없는 ZIP입니다"}
        files.forEach {entry->val name=entry.name.removePrefix(prefix)
            if(name in setOf("server.properties","eula.txt","server-manager-runtime.json"))require(entry.size<=if(name=="server.properties")2L*1024*1024 else 65536L) {"서버 설정 파일이 너무 큽니다: $name"}
        }
        fun text(path:String):String?=zip.getEntry(prefix+path)?.takeIf {it.size in 0..65536}?.let {zip.getInputStream(it).use {s->s.readNBytes(65537).also {b->require(b.size<=65536)}.toString(Charsets.UTF_8)}}
        val warnings=mutableListOf("기존 월드·모드·설정을 새 서버로 복사합니다. 업로드한 스크립트·설치기·서버는 자동 실행하지 않습니다.")
        if(files.size!=entries.size)warnings.add("인식한 서버 폴더 바깥의 파일 ${entries.size-files.size}개는 가져오지 않습니다.")
        val metadata=text("server-manager-runtime.json")?.let {runCatching {mapper.readTree(it)}.getOrNull()}
        var loader=metadata?.path("loader")?.asText()?.takeIf {it in Launchers.types}
        var minecraft=metadata?.path("minecraft")?.asText()?.takeIf {it.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))}
        var version=metadata?.path("loaderVersion")?.asText()?.takeIf {it.matches(Regex("[0-9]+(?:\\.[0-9]+)*(?:-[a-zA-Z0-9.]+)?"))}
        val jars=names.filter {'/' !in it && it.endsWith(".jar",true) && !it.contains("installer",true)}.sorted()
        var jar=metadata?.path("entryJar")?.asText()?.takeIf {it in jars}
        if(loader==null) {
            val neo=Regex("libraries/net/neoforged/neoforge/([^/]+)/(?:unix|win)_args\\.txt").findAll(names.joinToString("\n")).map {it.groupValues[1]}.distinct().toList()
            val forge=Regex("libraries/net/minecraftforge/forge/([^/]+)/(?:unix|win)_args\\.txt").findAll(names.joinToString("\n")).map {it.groupValues[1]}.distinct().toList()
            if(neo.size==1) {loader="NeoForge";version=neo.single();val p=version.split('.');if(p.size>=2)minecraft=if(p[0].toIntOrNull() in 20..25)"1.${p[0]}"+(if(p[1]=="0")"" else ".${p[1]}") else "${p[0]}.${p[1]}"}
            else if(forge.size==1 && '-' in forge.single()){loader="Forge";minecraft=forge.single().substringBefore('-');version=forge.single().substringAfter('-')}
            else if("fabric-server-launch.jar" in names){loader="Fabric";jar="fabric-server-launch.jar";version=names.mapNotNull {Regex("^libraries/net/fabricmc/fabric-loader/([^/]+)/").find(it)?.groupValues?.get(1)}.distinct().singleOrNull()}
            else {
                val paper=jars.mapNotNull {j->Regex("^paper-([0-9.]+)-([0-9]+)\\.jar$").matchEntire(j)?.let {Triple(j,it.groupValues[1],it.groupValues[2])}}.singleOrNull()
                val vanilla=jars.mapNotNull {j->Regex("^(?:minecraft_server[.-]|server-)([0-9.]+)\\.jar$").matchEntire(j)?.let {j to it.groupValues[1]}}.singleOrNull()
                if(paper!=null){loader="Paper";jar=paper.first;minecraft=paper.second;version=paper.third}
                else if(vanilla!=null){loader="Vanilla";jar=vanilla.first;minecraft=vanilla.second;version=minecraft}
            }
        }
        if(jar==null)jar=if("server.jar" in jars)"server.jar" else jars.singleOrNull()
        if(loader==null || minecraft==null || version==null)warnings.add("서버 종류·Minecraft·로더 버전 중 인식하지 못한 값은 직접 지정하세요. JAR 파일 이름만으로 서버 종류를 확정하지 않습니다.")
        val required=metadata?.path("requiredJava")?.asInt()?.takeIf {it in setOf(17,21,25)}
        if(required==null)warnings.add("필요 Java 버전을 확인하세요. 기본 선택값 21은 이 ZIP의 Java 요구사항을 검증한 값이 아닙니다.")
        warnings.add("실행 파일이 없으면 가져온 뒤 공식 런처를 설치해야 합니다. 기존 서버 파일은 자동으로 다운로드·재설치하지 않습니다.")
        ServerArchivePlan(prefix.trimEnd('/').substringAfterLast('/').take(80).ifBlank {filename.dropLast(4).take(80)},prefix,files.size,files.sumOf {it.size},loader,minecraft,version,jar,required,jars,warnings)
    }
    fun extract(archive:Path,root:String,destination:Path,progress:(Int,Long)->Unit={_,_->}) {
        require(!destination.exists());destination.createDirectories()
        var files=0;var total=0L
        ZipFile(archive.toFile()).use {zip->entries(zip).filter {it.name.startsWith(root) && !ignored(it.name)}.forEach {entry->
            val name=entry.name.removePrefix(root).trimEnd('/');if(name.isEmpty())return@forEach
            val target=destination.resolve(relative(name)).normalize();require(target.startsWith(destination.normalize()))
            if(entry.isDirectory)target.createDirectories() else {
                target.parent.createDirectories();val crc=CRC32();var count=0L
                zip.getInputStream(entry).use {input->Files.newOutputStream(target,CREATE_NEW).use {out->
                    val buffer=ByteArray(64*1024)
                    while(true) {if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("압축 해제가 중단됐습니다");val n=input.read(buffer);if(n<0)break
                        count+=n;total+=n;require(count<=entry.size && total<=MAX_TOTAL) {"ZIP의 실제 압축 해제 크기가 선언된 한도를 넘었습니다"};crc.update(buffer,0,n);out.write(buffer,0,n);progress(files,total)
                    }
                }}
                require(count==entry.size && crc.value==entry.crc) {"손상된 ZIP 파일입니다: ${name.take(100)}"};files++;progress(files,total)
            }
        }}
    }
    internal fun copy(input:InputStream,destination:Path,limit:Long):Long {
        var bytes=0L
        Files.newOutputStream(destination,CREATE_NEW).use {out->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=limit){"업로드 청크 크기 한도를 넘었습니다"};out.write(buffer,0,n)}}
        return bytes
    }
}
