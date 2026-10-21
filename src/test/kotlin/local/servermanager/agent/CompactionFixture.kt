package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
internal fun compactionFixture(goal:String)=jacksonObjectMapper().writeValueAsString(mapOf("goal" to goal,"constraints" to emptyList<String>(),"facts" to emptyList<String>(),"unfinished" to listOf("요청 결과를 실제 근거로 검증한다")))
