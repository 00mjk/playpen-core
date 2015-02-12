package net.thechunk.playpen.coordinator;


import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.thechunk.playpen.networking.TransactionInfo;
import net.thechunk.playpen.p3.PackageManager;
import net.thechunk.playpen.protocol.Commands;
import net.thechunk.playpen.protocol.Protocol;
import net.thechunk.playpen.utils.AuthUtils;

import java.util.UUID;

public abstract class PlayPen {
    private static PlayPen instance = null;

    public static PlayPen get() {
        return instance;
    }

    public static int protocolVersion() {
        return 1;
    }

    public PlayPen() {
        instance = this;
    }

    public abstract String getServerId();

    public abstract PackageManager getPackageManager();

    public String generateId() {
        return getServerId() + "-" + UUID.randomUUID().toString();
    }

    public abstract boolean send(Protocol.Transaction message, String target);

    public abstract boolean receive(Protocol.AuthenticatedMessage auth, Channel from);

    public abstract boolean process(Commands.BaseCommand command, TransactionInfo info, String from);
}