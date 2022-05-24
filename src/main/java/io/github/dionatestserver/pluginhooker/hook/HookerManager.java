package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.hook.impl.bukkit.BukkitEventInjector;
import io.github.dionatestserver.pluginhooker.hook.impl.protocollib.ProtocolLibInjector;

import java.util.ArrayList;
import java.util.List;

public class HookerManager {

    private List<Injector> injectors = new ArrayList<>();

    public HookerManager() {
        injectors.add(new BukkitEventInjector());
        injectors.add(new ProtocolLibInjector());

        injectors.forEach(Injector::predefineClass);
    }
}
