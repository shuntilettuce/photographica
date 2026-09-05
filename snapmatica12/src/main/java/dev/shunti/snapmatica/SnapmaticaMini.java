package dev.shunti.snapmatica;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Snapmatica Mini — the camera's optics, on Forge 1.12.2.
 *
 * <p>Client only: everything this mod does is a post-process on the frame the client already
 * drew, plus the viewfinder that drives it. There is nothing for a server to agree about.
 */
@Mod(modid = SnapmaticaMini.MODID,
     name = "Snapmatica Mini",
     version = SnapmaticaMini.VERSION,
     clientSideOnly = true,
     acceptedMinecraftVersions = "[1.12.2]")
public class SnapmaticaMini {
    public static final String MODID   = "snapmatica";
    public static final String VERSION = "0.1.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        event.getModLog().info("Snapmatica Mini " + VERSION + " loading");
        ClientEvents.register();
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }
}
