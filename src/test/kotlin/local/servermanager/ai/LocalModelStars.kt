package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Execute only an AST-checked, file/network-free subset in an empty temp working directory. */
internal object LocalModelStars {
    val expected=(0..4).joinToString("\n") {" ".repeat(4-it)+"*".repeat(2*it+1)}
    fun execute(code:String,home:Path):Pair<Boolean,String> {
        require(code.length<=12000)
        val wrapper="""
import ast, json, sys, signal
code=json.load(sys.stdin)['code']
tree=ast.parse(code)
allowed={'Module','Assign','AugAssign','For','If','IfExp','Expr','Name','Load','Store','Constant','Call','keyword','BinOp','Add','Sub','Mult','FloorDiv','Mod','Compare','Eq','NotEq','Lt','LtE','Gt','GtE','BoolOp','And','Or','UnaryOp','USub','UAdd','List','Tuple','ListComp','comprehension','JoinedStr','FormattedValue','Attribute','FunctionDef','arguments','arg','Return','Pass'}
functions={n.name for n in ast.walk(tree) if isinstance(n,ast.FunctionDef)}
calls={'print','range','len','str','int','min','max','abs'}|functions
for node in ast.walk(tree):
    if type(node).__name__ not in allowed: raise ValueError('Disallowed syntax '+type(node).__name__)
    if isinstance(node,ast.Name) and node.id.startswith('_') and node.id!='__name__': raise ValueError('Private name')
    if isinstance(node,ast.Attribute) and node.attr not in {'center','ljust','rjust','join','strip','rstrip','append'}: raise ValueError('Disallowed attribute')
    if isinstance(node,ast.Call) and not (isinstance(node.func,ast.Name) and node.func.id in calls or isinstance(node.func,ast.Attribute)): raise ValueError('Disallowed call')
    if isinstance(node,ast.Constant) and isinstance(node.value,(int,float)) and abs(node.value)>1000: raise ValueError('Oversized constant')
def bounded_range(*args):
    r=range(*args)
    if len(r)>1000: raise ValueError('Oversized range')
    return r
safe={'print':print,'range':bounded_range,'len':len,'str':str,'int':int,'min':min,'max':max,'abs':abs}
# Keep generated code execution bounded independently of a cold interpreter startup.
if hasattr(signal,'SIGALRM'): signal.alarm(3)
exec(compile(tree,'fixture.py','exec'),{'__builtins__':safe,'__name__':'__main__'})
""".trimIndent()
        val p=ProcessBuilder("/usr/bin/python3","-I","-c",wrapper).directory(home.toFile()).redirectErrorStream(true).start()
        p.outputStream.use {it.write(jacksonObjectMapper().writeValueAsBytes(mapOf("code" to code)))}
        val captured=java.util.concurrent.CompletableFuture.supplyAsync {p.inputStream.use {stream->stream.readNBytes(4097)}}
        if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor();return false to "Python process startup/execution exceeded 15 seconds (generated execution alarm: 3 seconds)"}
        val output=captured.get(4,TimeUnit.SECONDS).toString(Charsets.UTF_8)
        val normalized=output.trimEnd('\n','\r').lines().joinToString("\n") {it.trimEnd()}
        return (p.exitValue()==0 && output.length<=4096 && normalized==expected) to output.take(4096)
    }
}
