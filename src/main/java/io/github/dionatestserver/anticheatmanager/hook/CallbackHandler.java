package io.github.dionatestserver.anticheatmanager.hook;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.SortedPacketListenerList;
import io.github.dionatestserver.anticheatmanager.Diona;
import io.github.dionatestserver.anticheatmanager.anticheat.DionaPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;

public class CallbackHandler {

    public boolean handleBukkitEvent(Plugin plugin, Event event) {
        DionaPlayer dionaPlayer = Diona.getInstance().getPlayerManager().getDionaPlayer(this.getPlayerByEvent(event));
        if (dionaPlayer == null) return false;

        if (event instanceof AsyncPlayerPreLoginEvent || event instanceof PlayerJoinEvent || event instanceof PlayerQuitEvent || event instanceof PlayerLoginEvent)
            return false;

        if (Diona.getInstance().getAnticheatManager().getLoadedAnticheat().stream().noneMatch(anticheat -> anticheat.getPlugin() == plugin))
            return false;

        if (dionaPlayer.getEnabledAnticheats().stream().anyMatch(anticheat -> anticheat.getPlugin() == plugin))
            return false;


        System.out.println(plugin.getName() + " | " + event.getEventName());
        return true;
    }

    public void handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {

    }

//    private boolean isPlayerEvent(Event event) {
//        if (event instanceof PlayerEvent) return true;
//        return this.hasField(event.getClass(), "player");
//    }

    private Player getPlayerByEvent(Event event) {
        try {
            if (event instanceof PlayerEvent) return ((PlayerEvent) event).getPlayer();

            if (event instanceof EntityDamageEvent) {
                if (event instanceof EntityDamageByEntityEvent) {
                    EntityDamageByEntityEvent damageByEntityEvent = (EntityDamageByEntityEvent) event;
                    if ((damageByEntityEvent).getDamager() instanceof Player)
                        return ((Player) (damageByEntityEvent).getDamager());
                } else {
                    EntityDamageEvent entityDamageEvent = (EntityDamageEvent) event;
                    if ((entityDamageEvent).getEntity() instanceof Player)
                        return ((Player) (entityDamageEvent).getEntity());
                }
            }

            Field playerField = this.getField(event.getClass(), "player");
            if (playerField != null) {
                if (!playerField.isAccessible()) playerField.setAccessible(true);
                return (Player) playerField.get(event);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }


    private Field getField(Class<?> aClass, String fieldName) {
        try {
            return aClass.getDeclaredField(fieldName);
        } catch (Exception ignored) {
        }
        return null;
    }
}
