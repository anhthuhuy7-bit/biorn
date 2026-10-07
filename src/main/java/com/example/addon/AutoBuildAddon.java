package com.example.addon;

import com.example.addon.modules.AutoBuild;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoBuildAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("AutoBuild");

    @Override
    public void onInitialize() {
        LOG.info("Loading AutoBuild addon");
        Modules.get().add(new AutoBuild());
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
