import javax.swing.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Render this installer's own Swing content, without capturing the user's desktop. */
public class RunnerSetupUiTest {
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                RunnerSetup.Wizard ui=new RunnerSetup.Wizard();
                try {
                    if(ui.relay.isSelected() || !ui.relay.isEnabled() || !ui.auto.isSelected() || !ui.install.isEnabled() || !ui.manager.isEnabled() || ui.code.getEchoChar()==0) throw new AssertionError("Initial wizard controls");
                    if(ui.progress.isIndeterminate()) throw new AssertionError("Progress should be idle");
                    if(args.length>1) {ui.useFolder.doClick();ui.serverId.setText("00000000-0000-0000-0000-000000000001");ui.folder.setText("/Users/friend/Minecraft/My Server");ui.jar.setText("paper-1.21.1.jar");}
                    ui.frame.setSize(900,ui.frame.getHeight());ui.frame.validate();
                    var panel=ui.frame.getContentPane();
                    BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
                    var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
                    ImageIO.write(image,"png",Path.of(args[0]).toFile());
                    System.out.println("Installer UI rendered; masked code, startup choice, TCP relay consent default OFF, initial enabled state checked.");
                } finally {ui.frame.dispose();}
            } catch(Exception failure) {throw new RuntimeException(failure);}
        });
        System.exit(0);
    }
}
