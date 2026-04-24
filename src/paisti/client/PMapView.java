package paisti.client;

import haven.Coord;
import haven.Coord2d;
import haven.Glob;
import haven.MapView;
import haven.Warning;
import paisti.world.WorldPersistence;

import java.io.IOException;

public class PMapView extends MapView {
    private int observedMapChangeSeq = -1;

    public PMapView(Coord sz, Glob glob, Coord2d cc, long plgob) {
	super(sz, glob, cc, plgob);
    }

    @Override
    protected void attached() {
	super.attached();
	if(ui instanceof PUI) {
	    PUI.of(ui).overlayManager().syncMapOverlayAttachment();
	}
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	tickWorldPersistence();
    }

    private void tickWorldPersistence() {
	if(!(ui instanceof PUI) || !(ui.gui instanceof PGameUI))
	    return;
	WorldPersistence worldPersistence = ((PGameUI) ui.gui).worldPersistence();
	if(worldPersistence == null)
	    return;

	int currentSeq = glob.map.chseq;
	if(currentSeq != observedMapChangeSeq) {
	    observedMapChangeSeq = currentSeq;
	    worldPersistence.enqueueMCacheGrids(glob.map.loadedGrids());
	}
	try {
	    worldPersistence.tick();
	} catch(IOException e) {
	    Warning.warn("Failed to update world persistence: %s", e);
	}
    }
}
