package livingrealms.net;

import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.research.ResearchTree;
import livingrealms.settlement.*;
import livingrealms.smithing.Smithing;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

/**
 * One generic packet each way: a kind string plus an NBT payload. Server to client: catalog, settlement view, nearby
 * plots, blueprints. Client to server: open, close, action, blueprint request.
 */
public final class Net {
    private static final String VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(LivingRealms.id("main"), () -> VERSION, VERSION::equals, VERSION::equals);
    /** player -> settlement whose manager they have open */
    private static final Map<UUID, UUID> WATCHING = new HashMap<>();

    public record Msg(String kind, CompoundTag data) {
        static void encode(Msg m, FriendlyByteBuf buf) {
            buf.writeUtf(m.kind);
            buf.writeNbt(m.data);
        }

        static Msg decode(FriendlyByteBuf buf) {
            return new Msg(buf.readUtf(), buf.readAnySizeNbt());
        }
    }

    public static void register() {
        // one registration for both directions: SimpleChannel looks up the index by class, so two would collide
        CHANNEL.messageBuilder(Msg.class, 0).encoder(Msg::encode).decoder(Msg::decode)
                .consumerMainThread((m, ctx) -> {
                    if (ctx.get().getDirection().getReceptionSide().isServer()) toServer(m, ctx);
                    else toClient(m, ctx);
                }).add();
    }

    static void toClient(Msg m, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> livingrealms.client.ClientNet.handle(m.kind, m.data));
        ctx.get().setPacketHandled(true);
    }

    static void toServer(Msg m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        SettlementManager mgr = SettlementManager.get(p.server);
        switch (m.kind) {
            case "open" -> {
                Settlement s = m.data.hasUUID("id") ? mgr.get(m.data.getUUID("id")) : null;
                if (s == null) s = mgr.at(p.level().dimension(), p.blockPosition());
                if (s == null) {
                    List<Settlement> own = mgr.ownedBy(p.getUUID());
                    if (!own.isEmpty()) s = own.get(0);
                }
                if (s == null) {
                    p.displayClientMessage(net.minecraft.network.chat.Component.literal("You are not in a settlement. Place a Town Charter to found one.")
                            .withStyle(net.minecraft.ChatFormatting.GOLD), true);
                    return;
                }
                openManager(p, s, m.data.getString("tab"));
            }
            case "close" -> WATCHING.remove(p.getUUID());
            case "action" -> {
                UUID id = m.data.getUUID("id");
                Actions.handle(p, id, m.data.getString("action"), m.data.getCompound("data"));
                Settlement s = mgr.get(id);
                if (s != null) sendView(p, s, false, "");
            }
            case "blueprint" -> {
                Blueprint b = BuildingCatalog.blueprint(m.data.getString("key"));
                if (b == null) {
                    // roads and other plot-held blueprints
                    for (Settlement s : mgr.all()) for (Plot pl : s.plots) if (pl.blueprintKey.equals(m.data.getString("key"))) b = pl.blueprint();
                }
                if (b != null) send(p, "blueprint", b.toTag());
            }
            default -> {}
        }
    }

    public static void send(ServerPlayer p, String kind, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Msg(kind, data));
    }

    public static void toServer(String kind, CompoundTag data) {
        CHANNEL.sendToServer(new Msg(kind, data));
    }

    // ------------------------------------------------------------------ server -> client

    public static CompoundTag catalog() {
        CompoundTag t = new CompoundTag();
        t.put("buildings", BuildingCatalog.toTag());
        t.put("research", ResearchTree.toTag());
        t.put("smithing", Smithing.toTag());
        return t;
    }

    public static void sendCatalog(ServerPlayer p) { send(p, "catalog", catalog()); }

    public static void sendCatalogToAll(MinecraftServer server) {
        CompoundTag c = catalog();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p, "catalog", c);
    }

    public static void openManager(ServerPlayer p, Settlement s) { openManager(p, s, ""); }

    public static void openManager(ServerPlayer p, Settlement s, String tab) {
        Simulation.recompute(s);
        Autonomy.back(p, s);
        WATCHING.put(p.getUUID(), s.id);
        sendView(p, s, true, tab);
    }

    static void sendView(ServerPlayer p, Settlement s, boolean open, String tab) {
        CompoundTag t = View.of(s, p);
        t.putBoolean("open", open);
        t.putString("tab", tab);
        send(p, "view", t);
    }

    public static void syncWatchers(MinecraftServer server, SettlementManager m) {
        Set<Settlement> sent = new HashSet<>();
        for (Iterator<Map.Entry<UUID, UUID>> it = WATCHING.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Settlement s = m.get(e.getValue());
            if (p == null || s == null) { it.remove(); continue; }
            if (s.dirty) { sendView(p, s, false, ""); sent.add(s); }
        }
        sent.forEach(s -> s.dirty = false);
    }

    /** Plans and building sites near each player, for the in-world ghosts and territory borders. */
    public static void syncPlots(MinecraftServer server, SettlementManager m) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ListTag towns = new ListTag();
            for (Settlement s : m.all()) {
                if (!s.dim.equals(p.level().dimension())) continue;
                double r = s.radius() + 96;
                if (s.center.distSqr(p.blockPosition()) > r * r) continue;
                CompoundTag t = new CompoundTag();
                t.putUUID("id", s.id);
                t.putString("name", s.name);
                t.putString("style", s.style);
                t.putLong("center", s.center.asLong());
                t.putInt("radius", s.radius());
                t.putBoolean("mine", p.getUUID().equals(s.owner));
                t.putBoolean("ai", s.isAi());
                ListTag plots = new ListTag();
                for (Plot pl : s.plots) if (pl.state != Plot.State.BUILT) plots.add(pl.toClient());
                t.put("plots", plots);
                ListTag all = new ListTag();
                for (Plot pl : s.plots) {
                    if (pl.typeId.equals("road")) continue;
                    var b = pl.box();
                    all.add(StringTag.valueOf(b.minX() + "," + b.minZ() + "," + b.maxX() + "," + b.maxZ()));
                }
                t.put("boxes", all);
                towns.add(t);
            }
            CompoundTag out = new CompoundTag();
            out.put("towns", towns);
            send(p, "plots", out);
        }
    }

    private Net() {}
}
