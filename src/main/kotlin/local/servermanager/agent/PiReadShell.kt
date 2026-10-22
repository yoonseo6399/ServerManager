package local.servermanager

/** Expands finite literal for-lists for auditing only. Never executes or evaluates shell code. */
internal object PiReadShell {
    fun normalize(command:String):String=run {
        var text=command
        val loop=Regex("\\bfor\\s+([A-Za-z_][A-Za-z_0-9]*)\\s+in\\s+([^;]+);\\s*do\\s+([\\s\\S]+?);\\s*done\\b")
        repeat(4) {
            require('\\' !in text)
            val outside=BooleanArray(text.length);var quoted:Char?=null
            for(i in text.indices) {outside[i]=quoted==null;val ch=text[i];if(quoted==null && ch in "'\"")quoted=ch else if(ch==quoted)quoted=null}
            require(quoted==null)
            // A quoted '; done' or a quoted whole loop is DATA, never a control-flow boundary.
            val m=loop.findAll(text).firstOrNull {m->outside[m.range.first] && outside[m.range.last-3] && outside[m.groups[2]!!.range.last+1] && outside[m.groups[3]!!.range.last+1]} ?: return@repeat
            val variable=m.groupValues[1];val values=m.groupValues[2].trim().split(Regex("\\s+"))
            require(values.size in 1..8 && values.all {it.matches(Regex("[A-Za-z0-9_./-]+")) && !it.startsWith('-')})
            val body=m.groupValues[3];require(!Regex("'[^']*\\$[^']*'").containsMatchIn(body))
            val expanded=values.joinToString(" && ") {v->body.replace("\${$variable}",v).replace(Regex("\\$"+Regex.escape(variable)+"(?![A-Za-z0-9_])")){v}}
            text=text.replaceRange(m.range,expanded);require(text.length<=12000)
        }
        val out=StringBuilder();var quote:Char?=null;var i=0
        while(i<text.length) {
            val c=text[i]
            if(quote!=null){out.append(c);if(c==quote)quote=null;i++;continue}
            if(c=='\'' || c=='"'){quote=c;out.append(c);i++;continue}
            val redirect=listOf("2>/dev/null","2>&1").firstOrNull {text.startsWith(it,i) && (i==0 || text[i-1].isWhitespace()) && (i+it.length==text.length || text[i+it.length].isWhitespace() || text[i+it.length] in ";|")}
            if(redirect!=null){i+=redirect.length;continue}
            when(c) {
                ';'->out.append(" && ")
                '|'->{require(text.getOrNull(i+1)!='|' && text.getOrNull(i-1)!='|');out.append(" && ")}
                else->out.append(c)
            }
            i++
        }
        require(quote==null)
        out.toString()
    }
}
