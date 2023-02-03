package io.github.dionatestserver.pluginhooker.utils;

import com.comphenix.protocol.ProtocolLibrary;
import io.netty.util.Version;

import java.util.Map;

public class NettyVersion {
    private static final String NETTY_COMMON_ID = "netty-common";
    private static final String NETTY_ALL_ID = "netty-all";
    private static NettyVersion version;
    private int major;
    private int minor;

    public static NettyVersion getVersion() {
        if (version == null) {
            version = detectVersion();
        }

        return version;
    }

    private static NettyVersion detectVersion() {
        Map<String, Version> nettyArtifacts = Version.identify();
        Version version = nettyArtifacts.get(NETTY_COMMON_ID);
        if (version == null) {
            version = nettyArtifacts.get(NETTY_ALL_ID);
        }

        return version != null ? new NettyVersion(version.artifactVersion()) : new NettyVersion(null);
    }

    public NettyVersion(String s) {
        if (s != null) {
            String[] split = s.split("\\.");

            try {
                this.major = Integer.parseInt(split[0]);
                this.minor = Integer.parseInt(split[1]);
            } catch (Throwable var4) {
                ProtocolLibrary.getPlugin().getLogger().warning("Could not detect netty version: '" + s + "'");
            }

        }
    }


    public int getMajor() {
        return this.major;
    }

    public int getMinor() {
        return this.minor;
    }

}