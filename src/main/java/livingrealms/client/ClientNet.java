package livingrealms.client;

import livingrealms.building.BuildingCatalog;
import livingrealms.client.ui.CityScreen;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.Smithing;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

public final class ClientNet {
    public static void handle(String kind, CompoundTag data) {
        switch (kind) {
            case "catalog" -> {
                BuildingCatalog.setClientTypes(BuildingCatalog.fromTag(data.getCompound("buildings")));
                ResearchTree.fromTag(data.getCompound("research"));
                Smithing.fromTag(data.getCompound("smithing"));
            }
            case "view" -> {
                ClientState.view = data;
                Minecraft mc = Minecraft.getInstance();
                if (data.getBoolean("open") && !data.getString("tab").equals("architect")) mc.setScreen(new CityScreen(data.getString("tab")));
            }
            case "recruit" -> livingrealms.client.ui.RecruitScreen.receive(data);
            case "plots" -> ClientState.setTowns(data);
            case "blueprint" -> ClientState.putBlueprint(data);
            case "quest" -> QuestToast.show(data);
            default -> {}
        }
    }

    private ClientNet() {}
}
