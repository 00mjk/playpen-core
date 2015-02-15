package net.thechunk.playpen.coordinator.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.Channel;
import lombok.extern.log4j.Log4j2;
import net.thechunk.playpen.Initialization;
import net.thechunk.playpen.coordinator.PlayPen;
import net.thechunk.playpen.coordinator.Server;
import net.thechunk.playpen.networking.TransactionInfo;
import net.thechunk.playpen.networking.TransactionManager;
import net.thechunk.playpen.p3.P3Package;
import net.thechunk.playpen.p3.PackageManager;
import net.thechunk.playpen.protocol.Commands;
import net.thechunk.playpen.protocol.Coordinator;
import net.thechunk.playpen.protocol.P3;
import net.thechunk.playpen.protocol.Protocol;
import net.thechunk.playpen.utils.AuthUtils;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Log4j2
public class Network extends PlayPen {

    public static Network get() {
        if(PlayPen.get() == null) {
            new Network();
        }

        return (Network)PlayPen.get();
    }

    private Map<String, LocalCoordinator> coordinators = new ConcurrentHashMap<>();

    private PackageManager packageManager = null;

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private Network() {
        super();
        packageManager = new PackageManager();
        Initialization.packageManager(packageManager);
    }

    public LocalCoordinator getCoordinator(String id) {
        return coordinators.getOrDefault(id, null);
    }

    @Override
    public String getServerId() {
        return "net";
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }

    @Override
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    @Override
    public boolean send(Protocol.Transaction message, String target) {
        LocalCoordinator coord = getCoordinator(target);
        if(coord == null) {
            log.error("Unable to send transaction " + message.getId() + ": target " + target + " is invalid");
            return false;
        }

        if(coord.getChannel() == null || !coord.getChannel().isActive()) {
            log.error("Coordinator channel is null or inactive (" + target + ")");
            return false;
        }

        if(!message.isInitialized()) {
            log.error("Transaction is not initialized (protobuf)");
            return false;
        }

        byte[] messageBytes = message.toByteArray();
        Protocol.AuthenticatedMessage auth = Protocol.AuthenticatedMessage.newBuilder()
                .setUuid(coord.getUuid())
                .setHash(AuthUtils.createHash(coord.getKey(), messageBytes))
                .setPayload(ByteString.copyFrom(messageBytes))
                .build();

        if(!auth.isInitialized()) {
            log.error("Message is not initialized (protobuf)");
            return false;
        }

        coord.getChannel().writeAndFlush(auth);
        return true;
    }

    @Override
    public boolean receive(Protocol.AuthenticatedMessage auth, Channel from) {
        LocalCoordinator local = getCoordinator(auth.getUuid());
        if(local == null) {
            log.error("Unknown coordinator on receive (" + auth.getUuid() + ")");
            return false;
        }

        if(!AuthUtils.validateHash(auth, local.getKey())) {
            log.error("Invalid hash on message from " + auth.getUuid());
            return false;
        }

        Protocol.Transaction transaction = null;
        try {
            transaction = Protocol.Transaction.parseFrom(auth.getPayload());
        }
        catch(InvalidProtocolBufferException e) {
            log.error("Unable to read transaction from message", e);
            return false;
        }

        TransactionManager.get().receive(transaction, local.getUuid());
        return true;
    }

    @Override
    public boolean process(Commands.BaseCommand command, TransactionInfo info, String from) {
        throw new NotImplementedException(); // TODO
    }

    protected boolean processSync(Commands.Sync command, TransactionInfo info, String from) {
        LocalCoordinator coord = getCoordinator(from);
        if(coord == null) {
            log.error("Can't process SYNC on invalid coordinator " + from);
            return false;
        }

        coord.setEnabled(false); // protection so we don't start any new tasks
                                 // while syncing this coordinator

        if(command.hasName()) {
            coord.setName(command.getName());
        }
        else {
            coord.setName(coord.getUuid());
        }

        coord.getResources().clear();
        for(Coordinator.Resource resource : command.getResourcesList()) {
            coord.getResources().put(resource.getName(), resource.getValue());
        }

        coord.getAttributes().clear();
        for(String attr : command.getAttributesList()) {
            coord.getAttributes().add(attr);
        }

        coord.getServers().clear();
        for(Coordinator.Server cmdServer : command.getServersList()) {
            Server server = new Server();
            server.setActive(true);
            server.setUuid(cmdServer.getUuid());
            server.setName(cmdServer.hasName() ? cmdServer.getName() : server.getUuid());
            server.setP3(packageManager.resolve(cmdServer.getP3().getId(), cmdServer.getP3().getVersion()));
            if(server.getP3() == null) {
                log.warn("Unknown P3 " + cmdServer.getP3().getId() + " at " +
                    cmdServer.getP3().getVersion() + " for server " + server.getName());
            }

            for(Coordinator.Property prop : cmdServer.getPropertiesList()) {
                server.getProperties().put(prop.getName(), prop.getValue());
            }

            coord.getServers().put(server.getUuid(), server);
        }

        coord.setEnabled(command.getEnabled());
        log.info("Sync " + coord.getName() + " with " + coord.getServers().size()
                + " servers (" + (coord.isEnabled() ? "enabled" : "not enabled") + ")");
        return true;
    }

    protected boolean sendProvision(String target, P3Package p3, String name, Map<String, String> properties) {
        if(!p3.isResolved()) {
            log.error("Cannot pass an unresolved package to sendProvision");
            return false;
        }

        LocalCoordinator coord = getCoordinator(target);
        if(coord == null) {
            log.error("Unknown coordinator " + target + " for sendProvision");
            return false;
        }

        if(!coord.isEnabled()) {
            log.error("Coordinator " + target + " is not enabled for sendProvision");
            return false;
        }

        Server server = coord.createServer(p3, name, properties);
        if(server == null) {
            log.error("Unable to register server locally before sending for sendProvision");
            return false;
        }

        P3.P3Meta meta = P3.P3Meta.newBuilder()
                .setId(p3.getId())
                .setVersion(p3.getVersion())
                .build();

        Coordinator.Server.Builder serverBuilder = Coordinator.Server.newBuilder()
                .setP3(meta)
                .setUuid(server.getUuid());
        if(name != null)
            serverBuilder.setName(name);

        if(properties != null) {
            for(Map.Entry<String, String> entry : properties.entrySet()) {
                Coordinator.Property prop = Coordinator.Property.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getValue())
                        .build();

                serverBuilder.addProperties(prop);
            }
        }

        Commands.Provision provision = Commands.Provision.newBuilder()
                .setServer(serverBuilder.build())
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.PROVISION)
                .setExtension(Commands.Provision.command, provision)
                .build();

        TransactionInfo info = TransactionManager.get().begin();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.CREATE, command);
        if(message == null) {
            log.error("Unable to build message for provision");
            TransactionManager.get().cancel(info.getId());
            return false;
        }

        log.info("Sending provision of " + p3.getId() + " at " + p3.getVersion() + " to " + coord.getUuid());

        return TransactionManager.get().send(info.getId(), message, target);
    }

    protected boolean processProvisionResponse(Commands.ProvisionResponse command, TransactionInfo info, String from) {
        LocalCoordinator coord = getCoordinator(from);
        if(coord == null) {
            log.error("Cannot process PROVISION_RESPONSE on invalid coordinator " + from);
            return false;
        }

        Commands.BaseCommand previous = info.getTransaction().getPayload();
        if(previous == null || previous.getType() != Commands.BaseCommand.CommandType.PROVISION) {
            log.error("PROVISION_RESPONSE expects transaction to have previously contained a PROVISION command");
            return false;
        }

        String serverId = previous.getExtension(Commands.Provision.command).getServer().getUuid();
        Server server = coord.getServers().getOrDefault(serverId, null);
        if(server == null) {
            log.error("Unknown server " + serverId + " on PROVISION_RESPONSE");
            return false;
        }

        if(command.getOk()) {
            server.setActive(true);
            log.info("Server " + serverId + " on " + coord.getUuid() + " has been activated (provision response)");
            return true;
        }
        else {
            coord.getServers().remove(serverId);
            log.warn("Server " + serverId + " on " + coord.getUuid() + " failed to activate (provision response)");
            return false;
        }
    }

    protected boolean processPackageRequest(Commands.PackageRequest command, TransactionInfo info, String from) {
        LocalCoordinator coord = getCoordinator(from);
        if(coord == null) {
            log.error("Cannot process PACKAGE_REQUEST on invalid coordinator " + from);
            return false;
        }

        String id = command.getP3().getId();
        String version = command.getP3().getVersion();
        log.info("Package " + id + " at " + version + " requested by " + from);

        P3Package p3 = packageManager.resolve(id, version);
        if(p3 == null) {
            log.error("Unable to resolve package " + id + " at " + version + " for " + from);
            return sendPackageResponseFailure(from, info.getId());
        }

        return sendPackageResponse(from, info.getId(), p3);
    }

    protected boolean sendPackageResponseFailure(String target, String tid) {
        TransactionInfo info = TransactionManager.get().getInfo(tid);
        if(info == null) {
            log.error("Unknown transaction " + tid + ", unable to send package");
            return false;
        }

        Commands.PackageResponse response = Commands.PackageResponse.newBuilder()
                .setOk(false)
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.PACKAGE_RESPONSE)
                .setExtension(Commands.PackageResponse.command, response)
                .build();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.COMPLETE, command);
        if(message == null) {
            log.error("Unable to build transaction for package response (failure)");
            return false;
        }

        return TransactionManager.get().send(info.getId(), message, target);
    }

    protected boolean sendPackageResponse(String target, String tid, P3Package p3) {
        if(!p3.isResolved()) {
            log.error("Cannot pass an unresolved package to sendPackage");
            return false;
        }

        TransactionInfo info = TransactionManager.get().getInfo(tid);
        if(info == null) {
            log.error("Unknown transaction " + tid + ", unable to send package");
            return false;
        }

        P3.P3Meta meta = P3.P3Meta.newBuilder()
                .setId(p3.getId())
                .setVersion(p3.getVersion())
                .build();

        byte[] packageBytes = null;
        try {
            packageBytes = Files.readAllBytes(Paths.get(p3.getLocalPath()));
        }
        catch(IOException e) {
            log.error("Unable to read package data", e);
            return false;
        }

        P3.PackageData data = P3.PackageData.newBuilder()
                .setMeta(meta)
                .setData(ByteString.copyFrom(packageBytes))
                .build();

        Commands.PackageResponse response = Commands.PackageResponse.newBuilder()
                .setOk(true)
                .setData(data)
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.PACKAGE_RESPONSE)
                .setExtension(Commands.PackageResponse.command, response)
                .build();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.COMPLETE, command);
        if(message == null) {
            log.error("Unable to build transaction for package response");
            return false;
        }

        return TransactionManager.get().send(info.getId(), message, target);
    }

    protected boolean sendDeprovision(String target, String serverId, boolean force) {
        LocalCoordinator coord = getCoordinator(target);
        if(coord == null) {
            log.error("Cannot send DEPROVISION on invalid coordinator " + target);
            return false;
        }

        Server server = coord.getServers().getOrDefault(serverId, null);
        if(server == null) {
            log.error("Cannot send DEPROVISION on invalid server " + serverId + " on coordinator " + target);
            return false;
        }

        server.setActive(false);

        Commands.Deprovision deprovision = Commands.Deprovision.newBuilder()
                .setUuid(serverId)
                .setForce(force)
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.DEPROVISION)
                .setExtension(Commands.Deprovision.command, deprovision)
                .build();

        TransactionInfo info = TransactionManager.get().begin();
        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.SINGLE, command);
        if(message == null) {
            log.error("Unable to build transaction for DEPROVISION");
            TransactionManager.get().cancel(info.getId());
            return false;
        }

        return TransactionManager.get().send(info.getId(), message, target);
    }

    protected boolean processServerShutdown(Commands.ServerShutdown command, TransactionInfo info, String from) {
        LocalCoordinator coord = getCoordinator(from);
        if(coord == null) {
            log.error("Cannot process SERVER_SHUTDOWN on invalid coordinator " + from);
            return false;
        }

        Server server = coord.getServers().getOrDefault(command.getUuid(), null);
        if(server == null) {
            log.error("Cannot process SERVER_SHUTDOWN on invalid server " + command.getUuid() + " on coordinator " + from);
            return false;
        }

        server.setActive(false);
        coord.getServers().remove(server.getUuid());
        log.info("Server " + server.getUuid() + " shutdown on " + coord.getUuid());

        return true;
    }

    protected boolean sendShutdown(String target) {
        LocalCoordinator coord = getCoordinator(target);
        if(coord == null) {
            log.error("Cannot send SHUTDOWN on invalid coordinator " + target);
            return false;
        }

        coord.setEnabled(false);

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.SHUTDOWN)
                .build();

        TransactionInfo info = TransactionManager.get().begin();
        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.SINGLE, command);
        if(message == null) {
            log.error("Unable to build transaction for SHUTDOWN");
            TransactionManager.get().cancel(info.getId());
            return false;
        }

        log.info("Shutting down coordinator " + target);

        return TransactionManager.get().send(info.getId(), message, target);
    }
}
