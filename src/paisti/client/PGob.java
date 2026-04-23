package paisti.client;

import haven.*;
import me.ender.CustomizeVarMat;

import java.util.*;

/**
 * Paisti Gob subclass with enriched inspect tooltip.
 * <p>
 * The vanilla {@link Gob#inspect(boolean)} shows resource name, sdt, and
 * materials. This override adds gob id, sdt in multiple formats, attribute
 * list, and overlay list — useful for development and debugging.
 */
public class PGob extends Gob {

    public PGob(Glob glob, Coord2d c, long id) {
	super(glob, c, id);
    }

    /* ---- rich inspect tooltip ---- */

    @Override
    public String inspect(boolean full) {
	if(!full) {
	    return super.inspect(false);
	}

	String info = String.format("%s [%d]", resid(), sdt());
	List<String> lines = new ArrayList<>();
	lines.add(info);
	lines.add(String.format("id: %d", id));

	int sdt = sdt();
	lines.add(String.format("sdt-dec: %d", sdt));
	lines.add(String.format("sdt-hex: 0x%s", Integer.toHexString(sdt)));
	lines.add(String.format("sdt-bytes: %s", inspectSdtBytes()));
	lines.add(String.format("sdt-bits: %s", inspectSdtBits(sdt)));

	String attribs = inspectAttribs();
	if(attribs != null) {
	    lines.add(String.format("attribs: %s", attribs));
	}

	String overlays = inspectOverlays();
	if(overlays != null) {
	    lines.add(String.format("overlays: %s", overlays));
	}

	String mats = CustomizeVarMat.formatMaterials(this);
	if(mats != null) {
	    lines.add(mats);
	}
	return String.join("\n", lines);
    }

    /* ---- inspect helpers ---- */

    private String inspectSdtBytes() {
	Message sdt = sdtm();
	if((sdt == null) || sdt.eom()) {
	    return "none";
	}

	byte[] data = sdt.bytes();
	StringBuilder buf = new StringBuilder();
	for(int i = 0; i < data.length; i++) {
	    if(i > 0) {
		buf.append(' ');
	    }
	    buf.append(String.format("%02x", data[i] & 0xff));
	}
	return buf.toString();
    }

    private static String inspectSdtBits(int sdt) {
	return Integer.toBinaryString(sdt);
    }

    private String inspectAttribs() {
	Map<Class<? extends GAttrib>, GAttrib> attrs = cloneattrs();
	if(attrs.isEmpty()) {
	    return "none";
	}

	List<String> names = new ArrayList<>();
	for(GAttrib attr : attrs.values()) {
	    if(attr != null) {
		names.add(attr.getClass().getSimpleName());
	    }
	}
	if(names.isEmpty()) {
	    return "none";
	}
	Collections.sort(names);
	return String.join(", ", names);
    }

    private String inspectOverlays() {
	Collection<Overlay> overlays;
	synchronized(ols) {
	    overlays = new ArrayList<>(ols);
	}

	List<String> names = new ArrayList<>();
	for(Overlay ol : overlays) {
	    String name = inspectOverlay(ol);
	    if(name != null) {
		names.add(name);
	    }
	}
	if(names.isEmpty()) {
	    return "none";
	}
	Collections.sort(names);
	return String.join(", ", names);
    }

    private String inspectOverlay(Overlay ol) {
	if(ol == null) {
	    return null;
	}
	Indir<Resource> res = null;
	if(ol.sm instanceof OCache.OlSprite) {
	    res = ((OCache.OlSprite)ol.sm).res;
	} else if(ol.sm instanceof Sprite.Mill.FromRes) {
	    res = ((Sprite.Mill.FromRes)ol.sm).res;
	}
	if(res != null) {
	    try {
		return res.get().name;
	    } catch(Loading ignored) {
		return "loading";
	    }
	}
	if((ol.spr != null) && (ol.spr.res != null)) {
	    return ol.spr.res.name;
	}
	if(ol.spr != null) {
	    return String.format("%s#%d", ol.spr.getClass().getSimpleName(), ol.id);
	}
	if(ol.sm != null) {
	    return String.format("%s#%d", ol.sm.getClass().getSimpleName(), ol.id);
	}
	return String.format("overlay#%d", ol.id);
    }
}
