package dev.nano.ndidisplays.block;

/**
 * Everything that can be bolted into an equipment rack slot. Mesh names refer to
 * {@code assets/ndidisplays/meshes/<mesh>.obj} with a matching palette atlas in
 * {@code textures/entity/}.
 */
public enum RackUnitType {
    WEB("rack_web", "web_module"),
    PDU("rack_pdu", "rack_pdu"),
    SWITCH("rack_switch", "rack_switch"),
    PATCH("rack_patch", "rack_patch"),
    RECORDER("rack_recorder", "rack_recorder"),
    SYNC("rack_sync", "rack_sync"),
    BLANK("rack_blank", "rack_blank");

    public final String mesh;
    /** Registry name of the unit's item. */
    public final String itemName;

    RackUnitType(String mesh, String itemName) {
        this.mesh = mesh;
        this.itemName = itemName;
    }
}
