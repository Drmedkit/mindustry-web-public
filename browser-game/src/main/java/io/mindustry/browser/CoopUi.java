package io.mindustry.browser;

import arc.Core;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.serialization.Jval;
import io.mindustry.browser.net.RelayNetProvider;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.HostDialog;
import mindustry.ui.dialogs.JoinDialog;
import org.teavm.jso.JSBody;

/**
 * Browser co-op screens. Hosting opens a room and shows its code and link;
 * joining takes a room code or a pick from the public room list.
 */
final class CoopUi implements RelayNetProvider.RoomListener{
    private CoopUi(){
    }

    static final CoopUi instance = new CoopUi();
    static @Nullable RelayNetProvider relay;

    /** Replaces the desktop host and join dialogs once the UI exists. */
    static void install(){
        Vars.ui.host = new HostDialog(){
            @Override
            public Dialog show(){
                // No name, port or port-forwarding step: rooms are opened on the relay.
                ensurePlayerName();
                runHost();
                return this;
            }
        };
        JoinGameDialog join = new JoinGameDialog();
        Vars.ui.join = new JoinDialog(){
            @Override
            public Dialog show(){
                ensurePlayerName();
                return join.show();
            }
        };
    }

    /** Joins the room from a shared {@code ?room=CODE} link. */
    static void joinFromLink(String code){
        ensurePlayerName();
        Vars.ui.join.connect(code, Vars.port);
    }

    static void ensurePlayerName(){
        if(Core.settings.getString("name", "").trim().isEmpty()){
            String name = "Builder" + (100 + (int)(Math.random() * 900));
            Core.settings.put("name", name);
            Vars.player.name = name;
        }
    }

    @Override
    public void opened(String code){
        // Hosting starts from the pause menu; close it so the code is not hidden behind it.
        if(Vars.ui.paused != null) Vars.ui.paused.hide();
        String link = pageOrigin() + "/?room=" + code;
        BaseDialog dialog = new BaseDialog("Co-op room open");
        dialog.cont.add("Friends join with this code:").row();
        dialog.cont.add("[accent]" + code).fontScale(2.5f).pad(12f).row();
        dialog.cont.add("or by opening this link:").row();
        dialog.cont.add(link).color(Pal.lightishGray).wrap().width(420f).labelAlign(Align.center).pad(6f).row();
        dialog.cont.check("List this game publicly", relay != null && relay.isListed(), listed -> {
            if(relay != null) relay.setListed(listed, CoopUi::listing);
        }).padTop(10f).left().row();
        dialog.cont.add("[lightgray]Keep this tab open: the game runs here. Your campaign progress stays yours.")
            .wrap().width(420f).labelAlign(Align.center).padTop(12f);
        dialog.buttons.button("Copy link", Icon.copy, () -> {
            Core.app.setClipboardText(link);
            Vars.ui.showInfoToast("Link copied", 2f);
        }).size(210f, 64f);
        dialog.addCloseButton();
        dialog.show();
    }

    @Override
    public void lost(String reason){
        Vars.net.closeServer();
        Vars.ui.showErrorMessage(reason);
    }

    /** The public listing: the host's name, the map and the mode. Re-read on every refresh. */
    static String listing(){
        String map = Vars.state.map == null ? "" : Vars.state.map.name();
        String mode = Vars.state.isCampaign() ? "campaign" : Vars.state.rules.pvp ? "pvp" : "co-op";
        return "{\"type\":\"listing\",\"public\":true"
            + ",\"name\":\"" + jsonText(Vars.player.name + "'s game") + "\""
            + ",\"map\":\"" + jsonText(map) + "\""
            + ",\"mode\":\"" + mode + "\"}";
    }

    private static String jsonText(String text){
        return Strings.stripColors(text).replace("\\", "").replace("\"", "'").replaceAll("[\\p{Cntrl}]", "");
    }

    /** Code entry plus the public room list. */
    private static final class JoinGameDialog extends BaseDialog{
        private final Table rooms = new Table();
        private String code = "";

        JoinGameDialog(){
            super("Join a game");
            cont.table(row -> {
                row.add("Room code: ").padRight(8f);
                TextField field = row.field("", text -> code = text).width(220f).get();
                field.setMessageText("K7Q2XM");
                row.button("Join", Icon.play, () -> join(code)).size(140f, 56f).padLeft(8f);
            }).padBottom(16f).row();
            cont.add("Public games").color(Pal.accent).left().row();
            cont.image().color(Pal.accent).height(3f).growX().padBottom(6f).row();
            cont.pane(rooms).width(560f).height(320f).row();
            buttons.button("@back", Icon.left, this::hide).size(210f, 64f);
            buttons.button("Refresh", Icon.refresh, this::refresh).size(210f, 64f);
            shown(this::refresh);
        }

        private void join(String input){
            hide();
            Vars.ui.join.connect(input, Vars.port);
        }

        private void refresh(){
            rooms.clear();
            rooms.add("[lightgray]Looking for games...");
            if(relay == null) return;
            relay.fetchLobby(json -> {
                rooms.clear();
                Jval list = null;
                try{
                    if(json != null) list = Jval.read(json).get("rooms");
                }catch(Throwable ignored){
                }
                if(list == null || !list.isArray() || list.asArray().isEmpty()){
                    rooms.add("[lightgray]No public games right now. Host one from the pause menu!").wrap().width(520f);
                    return;
                }
                for(Jval room : list.asArray()){
                    String roomCode = room.getString("code", "");
                    rooms.table(Styles.grayPanel, entry -> {
                        entry.left().margin(8f);
                        entry.table(text -> {
                            text.left();
                            text.add("[accent]" + room.getString("name", "Game")).left().row();
                            text.add("[lightgray]" + room.getString("map", "") + "  -  " + room.getString("mode", "")
                                + "  -  " + room.getInt("players", 0) + "/" + room.getInt("max", 8) + " players").left();
                        }).growX();
                        entry.button("Join", Icon.play, () -> join(roomCode)).size(120f, 50f);
                    }).growX().pad(4f).row();
                }
            });
        }
    }

    @JSBody(script = "return location.origin;")
    private static native String pageOrigin();
}
