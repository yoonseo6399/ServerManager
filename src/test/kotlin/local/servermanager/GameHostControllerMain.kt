package local.servermanager

import java.nio.file.Path

/** Separate-JVM controller used to prove a crashed control layer does not take the game with it. */
object GameHostControllerMain {
    @JvmStatic fun main(args:Array<String>) {
        val home=Path.of(args[0]);val game=Path.of(args[1])
        val client=GameHostClient.connect(home,"controller-crashing")
        val java=Path.of(System.getProperty("java.home"),"bin","java").toString()
        val started=client.start("srv",listOf(java,"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",game.toString(),"chatter"),game,System.getenv())
        println("PID ${started.process.pid()}");System.out.flush()
        Thread.sleep(600_000)
    }
}
